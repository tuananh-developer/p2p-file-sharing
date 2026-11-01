import { spawn } from "node:child_process";
import { access } from "node:fs/promises";
import { setTimeout as delay } from "node:timers/promises";
import { resolve } from "node:path";

const root = process.cwd();
const children = [];
let stopping = false;

function startChild(command, args, extraEnv = {}) {
  const child = spawn(command, args, {
    cwd: root,
    env: { ...process.env, ...extraEnv },
    stdio: "inherit",
    shell: false
  });
  children.push(child);
  child.once("exit", (code, signal) => {
    if (!stopping) {
      console.error(command + " exited (code=" + String(code) + ", signal=" + String(signal) + ").");
      void stop(code ?? 1);
    }
  });
  return child;
}

async function waitForVite(url) {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (response.ok) {
        return;
      }
    } catch {
      // Vite is still starting.
    }
    await delay(250);
  }
  throw new Error("Vite did not become ready within 30 seconds.");
}

async function runOnce(command, args) {
  await new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(command, args, { cwd: root, stdio: "inherit", shell: false });
    child.once("error", rejectPromise);
    child.once("exit", (code) => {
      if (code === 0) {
        resolvePromise();
      } else {
        rejectPromise(new Error(command + " failed with exit code " + String(code)));
      }
    });
  });
}

async function stop(exitCode = 0) {
  if (stopping) {
    return;
  }
  stopping = true;
  for (const child of children) {
    if (child.exitCode === null && !child.killed) {
      child.kill("SIGTERM");
    }
  }
  await delay(300);
  process.exit(exitCode);
}

process.on("SIGINT", () => void stop(0));
process.on("SIGTERM", () => void stop(0));

try {
  await access(resolve(root, "build/install/file_sharing/lib"));
  await runOnce(process.execPath, ["node_modules/typescript/bin/tsc", "-p", "tsconfig.electron.json"]);

  startChild(process.execPath, ["node_modules/typescript/bin/tsc", "-p", "tsconfig.electron.json", "--watch"]);
  startChild(process.execPath, ["node_modules/vite/bin/vite.js", "--host", "127.0.0.1"]);
  await waitForVite("http://127.0.0.1:5173");

  const electronCli = resolve(root, "node_modules/electron/cli.js");
  startChild(process.execPath, [electronCli, "."], {
    VITE_RENDERER_URL: "http://127.0.0.1:5173"
  });
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error));
  await stop(1);
}
