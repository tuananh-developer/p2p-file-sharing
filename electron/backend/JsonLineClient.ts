import { randomUUID } from "node:crypto";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { EventEmitter } from "node:events";

type ClientState = "STOPPED" | "STARTING" | "READY" | "STOPPING" | "FAILED";

interface StartOptions {
  cwd?: string;
  env?: NodeJS.ProcessEnv;
  startupTimeoutMs?: number;
}

interface PendingRequest {
  resolve(value: unknown): void;
  reject(reason: Error): void;
  timer: NodeJS.Timeout;
}

interface BridgeMessage {
  type?: unknown;
  id?: unknown;
  success?: unknown;
  data?: unknown;
  error?: unknown;
  event?: unknown;
}

export class BridgeCommandError extends Error {
  constructor(
    public readonly code: string,
    message: string
  ) {
    super(message);
    this.name = "BridgeCommandError";
  }
}

/**
 * Owns the Java JSON Lines child process. It is deliberately independent of
 * Electron so request correlation and process failure handling can be tested.
 */
export class JsonLineClient extends EventEmitter {
  private child: ChildProcessWithoutNullStreams | null = null;
  private state: ClientState = "STOPPED";
  private stdoutBuffer = "";
  private closeRequested = false;
  private readonly pending = new Map<string, PendingRequest>();
  private startupResolve: (() => void) | null = null;
  private startupReject: ((error: Error) => void) | null = null;

  getState(): ClientState {
    return this.state;
  }

  async start(executable: string, args: string[], options: StartOptions = {}): Promise<void> {
    if (this.child) {
      throw new Error("The Java backend process is already running.");
    }

    this.state = "STARTING";
    this.stdoutBuffer = "";
    this.closeRequested = false;

    await new Promise<void>((resolve, reject) => {
      const timeoutMs = options.startupTimeoutMs ?? 12_000;
      const startupTimer = setTimeout(() => {
        const error = new Error("Java backend did not report READY within " + timeoutMs + " ms.");
        this.state = "FAILED";
        this.rejectStartup(error);
        this.child?.kill("SIGTERM");
      }, timeoutMs);

      this.startupResolve = () => {
        clearTimeout(startupTimer);
        this.startupResolve = null;
        this.startupReject = null;
        resolve();
      };
      this.startupReject = (error) => {
        clearTimeout(startupTimer);
        this.startupResolve = null;
        this.startupReject = null;
        reject(error);
      };

      let child: ChildProcessWithoutNullStreams;
      try {
        child = spawn(executable, args, {
          cwd: options.cwd,
          env: options.env ?? process.env,
          stdio: ["pipe", "pipe", "pipe"],
          windowsHide: true,
          shell: false
        });
      } catch (error) {
        this.state = "FAILED";
        this.rejectStartup(asError(error));
        return;
      }

      this.child = child;
      child.stdout.setEncoding("utf8");
      child.stderr.setEncoding("utf8");
      child.stdout.on("data", (chunk: string) => this.consumeStdout(chunk));
      child.stderr.on("data", (chunk: string) => this.emit("stderr", String(chunk)));
      child.once("error", (error) => this.handleProcessError(child, error));
      child.once("exit", (code, signal) => this.handleExit(child, code, signal));
    });
  }

  async request<T>(
    command: string,
    payload: Record<string, unknown> = {},
    timeoutMs = 15_000
  ): Promise<T> {
    const child = this.child;
    if (!child || this.state !== "READY") {
      throw new Error("Java backend is not ready.");
    }
    if (!command || command.length > 64 || timeoutMs < 1) {
      throw new Error("Invalid bridge command or timeout.");
    }

    const id = randomUUID();
    const request = JSON.stringify({ id, command, payload }) + "\n";

    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error("Java backend request timed out: " + command));
      }, timeoutMs);

      this.pending.set(id, {
        resolve: (value) => resolve(value as T),
        reject,
        timer
      });

      child.stdin.write(request, (error) => {
        if (!error) {
          return;
        }
        const pending = this.pending.get(id);
        if (pending) {
          clearTimeout(pending.timer);
          this.pending.delete(id);
          pending.reject(error);
        }
      });
    });
  }

  async shutdown(gracefulTimeoutMs = 3_000): Promise<void> {
    const child = this.child;
    if (!child) {
      this.state = "STOPPED";
      return;
    }

    this.closeRequested = true;
    if (this.state === "READY") {
      try {
        await this.request("app.shutdown", {}, gracefulTimeoutMs);
      } catch (error) {
        this.emit("stderr", "Graceful Java shutdown did not complete: " + asError(error).message);
      }
    }

    this.state = "STOPPING";
    try {
      child.stdin.end();
    } catch {
      // The child may already have closed stdin.
    }

    if (await this.waitForExit(child, gracefulTimeoutMs)) {
      this.state = "STOPPED";
      return;
    }

    child.kill("SIGTERM");
    if (await this.waitForExit(child, 1_000)) {
      this.state = "STOPPED";
      return;
    }

    child.kill("SIGKILL");
    await this.waitForExit(child, 1_000);
    if (this.child === child) {
      this.child = null;
    }
    this.state = "STOPPED";
    this.rejectPending(new Error("Java backend was forcefully terminated during shutdown."));
  }

  private consumeStdout(chunk: string): void {
    this.stdoutBuffer += chunk;
    if (this.stdoutBuffer.length > 1_048_576 && !this.stdoutBuffer.includes("\n")) {
      this.failTransport("Java backend emitted a JSON Lines frame larger than 1 MiB.");
      return;
    }

    let newlineIndex = this.stdoutBuffer.indexOf("\n");
    while (newlineIndex >= 0) {
      let line = this.stdoutBuffer.slice(0, newlineIndex);
      this.stdoutBuffer = this.stdoutBuffer.slice(newlineIndex + 1);
      if (line.endsWith("\r")) {
        line = line.slice(0, -1);
      }
      if (line.length > 1_048_576) {
        this.failTransport("Java backend emitted an oversized JSON Lines frame.");
        return;
      } else if (line.trim().length > 0) {
        this.handleMessage(line);
      }
      newlineIndex = this.stdoutBuffer.indexOf("\n");
    }
  }

  private handleMessage(line: string): void {
    let message: BridgeMessage;
    try {
      message = JSON.parse(line) as BridgeMessage;
    } catch {
      this.failTransport("Java backend wrote invalid JSON to stdout.");
      return;
    }

    if (message.type === "response" && typeof message.id === "string") {
      const pending = this.pending.get(message.id);
      if (!pending) {
        return;
      }
      clearTimeout(pending.timer);
      this.pending.delete(message.id);

      if (message.success === true) {
        pending.resolve(message.data);
      } else {
        const error = isRecord(message.error) ? message.error : {};
        pending.reject(new BridgeCommandError(
          typeof error.code === "string" ? error.code : "INTERNAL_ERROR",
          typeof error.message === "string" ? error.message : "Java backend command failed."
        ));
      }
      return;
    }

    if (message.type === "event" && typeof message.event === "string") {
      const data = isRecord(message.data) ? message.data : {};
      if (message.event === "backendStatusChanged" && data.status === "READY") {
        this.state = "READY";
        this.startupResolve?.();
      } else if (message.event === "backendStatusChanged" && data.status === "STOPPED") {
        if (this.state === "READY") {
          this.state = "STOPPING";
        }
      }
      this.emit("event", { event: message.event, data });
    }
  }

  private failTransport(message: string): void {
    const error = new Error(message);
    this.state = "FAILED";
    this.stdoutBuffer = "";
    this.rejectStartup(error);
    this.rejectPending(error);
    this.emit("transportError", message);
    this.child?.kill("SIGTERM");
  }

  private handleProcessError(child: ChildProcessWithoutNullStreams, error: Error): void {
    if (this.child === child) {
      this.child = null;
    }
    this.state = this.closeRequested ? "STOPPED" : "FAILED";
    this.rejectStartup(error);
    this.rejectPending(new Error("Java backend process error: " + error.message));
    if (!this.closeRequested) {
      this.emit("transportError", error.message);
    }
  }

  private handleExit(
    child: ChildProcessWithoutNullStreams,
    code: number | null,
    signal: NodeJS.Signals | null
  ): void {
    if (this.child === child) {
      this.child = null;
    }
    const expected = this.closeRequested;
    this.state = expected ? "STOPPED" : "FAILED";
    const error = new Error(
      "Java backend exited unexpectedly (code=" + String(code) + ", signal=" + String(signal) + ")."
    );
    this.rejectStartup(error);
    this.rejectPending(error);
    this.emit("exit", { code, signal, expected });
    if (!expected) {
      this.emit("transportError", error.message);
    }
  }

  private waitForExit(child: ChildProcessWithoutNullStreams, timeoutMs: number): Promise<boolean> {
    if (this.child !== child) {
      return Promise.resolve(true);
    }
    return new Promise((resolve) => {
      let settled = false;
      const finish = (exited: boolean) => {
        if (settled) {
          return;
        }
        settled = true;
        clearTimeout(timer);
        child.removeListener("exit", onExit);
        resolve(exited);
      };
      const onExit = () => finish(true);
      const timer = setTimeout(() => finish(this.child !== child), timeoutMs);
      child.once("exit", onExit);
    });
  }

  private rejectStartup(error: Error): void {
    const reject = this.startupReject;
    this.startupResolve = null;
    this.startupReject = null;
    reject?.(error);
  }

  private rejectPending(error: Error): void {
    for (const [id, pending] of this.pending) {
      clearTimeout(pending.timer);
      this.pending.delete(id);
      pending.reject(error);
    }
  }
}

function asError(error: unknown): Error {
  return error instanceof Error ? error : new Error(String(error));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
