import {
  app,
  BrowserWindow,
  dialog,
  ipcMain,
  session,
  type OpenDialogOptions
} from "electron";
import { constants as fsConstants } from "node:fs";
import { createHash, randomUUID } from "node:crypto";
import { existsSync, statSync } from "node:fs";
import { open, realpath, rename, unlink, type FileHandle } from "node:fs/promises";
import { basename, dirname, join, resolve } from "node:path";
import { JsonLineClient } from "./backend/JsonLineClient";
import type { BackendEvent, BackendStatusSnapshot } from "./shared/contracts";

const JAVA_MAIN_CLASS = "com.tanh.file_sharing.FileSharingApplication";
const bridge = new JsonLineClient();

let mainWindow: BrowserWindow | null = null;
let backendStatus: BackendStatusSnapshot = { status: "STARTING" };
let backendStart: Promise<BackendStatusSnapshot> | null = null;
let isQuitting = false;
let resumableDirectory: string | null = null;

const MAX_INTERNET_FILE_BYTES = 50 * 1024 * 1024 * 1024;
const MAX_INTERNET_CHUNK_BYTES = 48 * 1024;

interface OutgoingFileHandle {
  handle: FileHandle;
  fileName: string;
  size: number;
  sha256: string;
}

interface IncomingFileHandle {
  handle: FileHandle;
  fileName: string;
  targetPath: string;
  temporaryPath: string;
  size: number;
  nextOffset: number;
  hash: ReturnType<typeof createHash>;
}

const outgoingFileHandles = new Map<string, OutgoingFileHandle>();
const incomingFileHandles = new Map<string, IncomingFileHandle>();

function requireHandleId(value: unknown): string {
  return requireUuid(value, "handleId");
}

async function getCanonicalSharedDirectory(): Promise<string> {
  requireBackendReady();
  const data = await command<{ sharedDirectory: { configured: boolean; path: string | null } }>("share.getDirectory");
  const directory = data.sharedDirectory;
  if (!directory?.configured || typeof directory.path !== "string" || !directory.path.trim()) {
    throw new Error("Choose a shared directory before sending files over the Internet.");
  }
  return realpath(directory.path);
}

async function openInternetSharedFile(fileNameValue: unknown): Promise<{
  handleId: string;
  fileName: string;
  size: number;
  sha256: string;
}> {
  const fileName = requireFileName(fileNameValue);
  const directory = await getCanonicalSharedDirectory();
  const filePath = join(directory, fileName);
  if (dirname(filePath) !== directory) {
    throw new Error("The requested shared file is outside the configured directory.");
  }

  const noFollow = typeof fsConstants.O_NOFOLLOW === "number" ? fsConstants.O_NOFOLLOW : 0;
  const handle = await open(filePath, fsConstants.O_RDONLY | noFollow);
  try {
    const before = await handle.stat();
    if (!before.isFile() || before.size < 0 || before.size > MAX_INTERNET_FILE_BYTES) {
      throw new Error("The selected file is not a regular file or exceeds the 50 GiB transfer limit.");
    }

    const hash = createHash("sha256");
    const buffer = Buffer.allocUnsafe(1024 * 1024);
    let offset = 0;
    while (offset < before.size) {
      const length = Math.min(buffer.length, before.size - offset);
      const { bytesRead } = await handle.read(buffer, 0, length, offset);
      if (bytesRead !== length) {
        throw new Error("The shared file changed or became unreadable while hashing.");
      }
      hash.update(buffer.subarray(0, bytesRead));
      offset += bytesRead;
    }

    const after = await handle.stat();
    if (after.size !== before.size || after.mtimeMs !== before.mtimeMs) {
      throw new Error("The shared file changed while its SHA-256 digest was calculated.");
    }

    const handleId = randomUUID();
    const snapshot = {
      handleId,
      fileName,
      size: before.size,
      sha256: hash.digest("hex")
    };
    outgoingFileHandles.set(handleId, { handle, ...snapshot });
    return snapshot;
  } catch (error) {
    await handle.close().catch(() => undefined);
    throw error;
  }
}

async function readInternetSharedFileChunk(input: {
  handleId?: unknown;
  offset?: unknown;
  length?: unknown;
}): Promise<Uint8Array> {
  const handleId = requireHandleId(input?.handleId);
  const source = outgoingFileHandles.get(handleId);
  if (!source) {
    throw new Error("The shared file handle is no longer available.");
  }
  const offset = input?.offset;
  const length = input?.length;
  if (!Number.isSafeInteger(offset) || typeof offset !== "number" || offset < 0 ||
      !Number.isInteger(length) || typeof length !== "number" ||
      length < 1 || length > MAX_INTERNET_CHUNK_BYTES || offset + length > source.size) {
    throw new Error("The requested file chunk has an invalid offset or length.");
  }

  const buffer = Buffer.allocUnsafe(length);
  const { bytesRead } = await source.handle.read(buffer, 0, length, offset);
  if (bytesRead !== length) {
    throw new Error("The shared file changed or became unreadable during transfer.");
  }
  return new Uint8Array(buffer);
}

async function closeInternetSharedFile(handleIdValue: unknown): Promise<void> {
  const handleId = requireHandleId(handleIdValue);
  const source = outgoingFileHandles.get(handleId);
  if (!source) return;
  outgoingFileHandles.delete(handleId);
  await source.handle.close();
}

async function beginInternetReceive(input: { fileName?: unknown; size?: unknown }): Promise<{
  handleId: string;
  fileName: string;
  size: number;
} | null> {
  const fileName = requireFileName(input?.fileName);
  const size = input?.size;
  if (typeof size !== "number" || !Number.isSafeInteger(size) || size < 0 || size > MAX_INTERNET_FILE_BYTES) {
    throw new Error("The incoming file size is invalid or exceeds the 50 GiB transfer limit.");
  }

  const result = mainWindow
    ? await dialog.showSaveDialog(mainWindow, {
      title: "Save incoming peer file",
      defaultPath: fileName,
      buttonLabel: "Save file"
    })
    : await dialog.showSaveDialog({ title: "Save incoming peer file", defaultPath: fileName, buttonLabel: "Save file" });
  if (result.canceled || !result.filePath) return null;

  const targetPath = resolve(result.filePath);
  const handleId = randomUUID();
  const temporaryPath = join(dirname(targetPath), "." + basename(targetPath) + "." + handleId + ".part");
  const handle = await open(temporaryPath, "wx", 0o600);
  incomingFileHandles.set(handleId, {
    handle,
    fileName: basename(targetPath),
    targetPath,
    temporaryPath,
    size,
    nextOffset: 0,
    hash: createHash("sha256")
  });
  return { handleId, fileName: basename(targetPath), size };
}

async function writeInternetReceiveChunk(input: {
  handleId?: unknown;
  offset?: unknown;
  chunk?: unknown;
}): Promise<number> {
  const handleId = requireHandleId(input?.handleId);
  const receive = incomingFileHandles.get(handleId);
  if (!receive) throw new Error("The incoming file handle is no longer available.");
  const chunk = input?.chunk;
  if (!(chunk instanceof Uint8Array) || chunk.byteLength < 1 || chunk.byteLength > MAX_INTERNET_CHUNK_BYTES) {
    throw new Error("An incoming chunk must contain between 1 byte and 48 KiB.");
  }
  const offset = input?.offset;
  if (typeof offset !== "number" || !Number.isSafeInteger(offset) ||
      offset !== receive.nextOffset || offset + chunk.byteLength > receive.size) {
    throw new Error("Incoming file chunks must be sequential and within the advertised file size.");
  }

  const buffer = Buffer.from(chunk);
  let written = 0;
  while (written < buffer.length) {
    const result = await receive.handle.write(buffer, written, buffer.length - written, offset + written);
    if (result.bytesWritten <= 0) throw new Error("Could not write the incoming file chunk.");
    written += result.bytesWritten;
  }
  receive.hash.update(buffer);
  receive.nextOffset += buffer.length;
  return receive.nextOffset;
}

async function abortInternetReceive(handleIdValue: unknown): Promise<void> {
  const handleId = requireHandleId(handleIdValue);
  const receive = incomingFileHandles.get(handleId);
  if (!receive) return;
  incomingFileHandles.delete(handleId);
  await receive.handle.close().catch(() => undefined);
  await unlink(receive.temporaryPath).catch(() => undefined);
}

async function completeInternetReceive(input: { handleId?: unknown; sha256?: unknown }): Promise<{
  fileName: string;
  size: number;
  sha256: string;
}> {
  const handleId = requireHandleId(input?.handleId);
  const receive = incomingFileHandles.get(handleId);
  if (!receive) throw new Error("The incoming file handle is no longer available.");
  const expectedHash = requireFingerprint(input?.sha256).toLowerCase();
  if (receive.nextOffset !== receive.size) {
    throw new Error("The incoming file is incomplete; refusing to finalize it.");
  }
  const actualHash = receive.hash.digest("hex");
  if (actualHash !== expectedHash) {
    await abortInternetReceive(handleId);
    throw new Error("The received file failed SHA-256 verification.");
  }

  await receive.handle.sync();
  await receive.handle.close();
  try {
    await rename(receive.temporaryPath, receive.targetPath);
  } catch (error) {
    await unlink(receive.temporaryPath).catch(() => undefined);
    incomingFileHandles.delete(handleId);
    throw error;
  }
  incomingFileHandles.delete(handleId);
  return { fileName: receive.fileName, size: receive.size, sha256: actualHash };
}

async function cleanupInternetFileHandles(): Promise<void> {
  const outgoing = [...outgoingFileHandles.keys()];
  const incoming = [...incomingFileHandles.keys()];
  await Promise.all(outgoing.map((handleId) => closeInternetSharedFile(handleId).catch(() => undefined)));
  await Promise.all(incoming.map((handleId) => abortInternetReceive(handleId).catch(() => undefined)));
}

function publishEvent(event: BackendEvent): void {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send("p2p:event", event);
  }
}

function setBackendStatus(status: BackendStatusSnapshot): void {
  backendStatus = status;
  publishEvent({
    event: "backendProcessStatusChanged",
    data: { status: status.status, error: status.error ?? null }
  });
}

bridge.on("event", (event: BackendEvent) => {
  if (event.event === "backendStatusChanged") {
    const state = event.data.status;
    if (state === "READY") {
      setBackendStatus({ status: "READY" });
    } else if (state === "STOPPED" && !isQuitting) {
      setBackendStatus({ status: "FAILED", error: "Java backend stopped unexpectedly." });
    }
  }
  publishEvent(event);
});

bridge.on("stderr", (message: string) => {
  // Keep Java diagnostics outside the JSON Lines stdout protocol.
  console.warn("[Java P2P Core]", message.trimEnd());
});

bridge.on("transportError", (message: string) => {
  if (!isQuitting) {
    setBackendStatus({ status: "FAILED", error: message });
  }
  publishEvent({ event: "backendProcessError", data: { message } });
});

bridge.on("exit", (result: { code: number | null; signal: NodeJS.Signals | null; expected: boolean }) => {
  if (result.expected || isQuitting) {
    setBackendStatus({ status: "STOPPED" });
  } else {
    setBackendStatus({
      status: "FAILED",
      error: "Java backend exited unexpectedly (code=" + String(result.code) + ")."
    });
  }
  publishEvent({ event: "backendProcessExited", data: result });
});

async function startBackend(): Promise<BackendStatusSnapshot> {
  if (backendStart) {
    return backendStart;
  }
  if (bridge.getState() === "READY") {
    return backendStatus;
  }

  backendStart = (async () => {
    setBackendStatus({ status: "STARTING" });
    try {
      if (bridge.getState() !== "STOPPED") {
        await bridge.shutdown(25_000);
      }

      const libDirectory = process.env.P2P_BACKEND_LIB_DIR
        ? resolve(process.env.P2P_BACKEND_LIB_DIR)
        : app.isPackaged
          ? join(process.resourcesPath, "backend", "lib")
          : join(app.getAppPath(), "build", "install", "file_sharing", "lib");

      if (!existsSync(libDirectory) || !statSync(libDirectory).isDirectory()) {
        throw new Error(
          "Java backend libraries were not found at " + libDirectory +
          ". Run ./gradlew --no-daemon installDist before starting the desktop app."
        );
      }

      const javaExecutable = resolveJavaExecutable();
      const classpath = join(libDirectory, "*");
      await bridge.start(javaExecutable, ["-cp", classpath, JAVA_MAIN_CLASS], {
        cwd: app.getPath("userData"),
        env: process.env,
        startupTimeoutMs: 15_000
      });
      if (bridge.getState() !== "READY") {
        throw new Error("Java backend exited before the desktop bridge became ready.");
      }
      setBackendStatus({ status: "READY" });
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      await bridge.shutdown(500).catch(() => undefined);
      setBackendStatus({ status: "FAILED", error: message });
    }
    return backendStatus;
  })();

  try {
    return await backendStart;
  } finally {
    backendStart = null;
  }
}

function resolveJavaExecutable(): string {
  const override = process.env.P2P_JAVA_EXECUTABLE;
  if (override && override.trim()) {
    return override.trim();
  }

  const javaHome = process.env.JAVA_HOME;
  if (javaHome && javaHome.trim()) {
    const executable = process.platform === "win32" ? "java.exe" : "java";
    const candidate = join(javaHome, "bin", executable);
    if (!existsSync(candidate)) {
      throw new Error("JAVA_HOME does not contain a Java executable: " + candidate);
    }
    return candidate;
  }
  return process.platform === "win32" ? "java.exe" : "java";
}

function requireBackendReady(): void {
  if (backendStatus.status !== "READY") {
    throw new Error(backendStatus.error ?? "Java backend is not ready.");
  }
}

async function command<T>(name: string, payload: Record<string, unknown> = {}): Promise<T> {
  requireBackendReady();
  return bridge.request<T>(name, payload);
}

function requireText(value: unknown, field: string, maxLength = 512): string {
  if (typeof value !== "string" || value.trim().length === 0 || value.trim().length > maxLength) {
    throw new Error(field + " must be a non-empty string no longer than " + maxLength + " characters.");
  }
  return value.trim();
}

function requirePort(value: unknown, field: string, allowAuto = false): number {
  const minimum = allowAuto ? 0 : 1;
  if (typeof value !== "number" || !Number.isInteger(value) || value < minimum || value > 65_535) {
    const range = allowAuto ? "0 and 65535" : "1 and 65535";
    throw new Error(field + " must be an integer between " + range + ".");
  }
  return value;
}

function requireFingerprint(value: unknown): string {
  const fingerprint = requireText(value, "fingerprint", 191);
  const normalized = fingerprint.replace(/[:\\s-]/g, "");
  if (!/^[0-9a-f]{64}$/i.test(normalized)) {
    throw new Error("fingerprint must be a SHA-256 certificate fingerprint (64 hexadecimal characters).");
  }
  return normalized.toUpperCase();
}

function requireUuid(value: unknown, field: string): string {
  const id = requireText(value, field, 64);
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(id)) {
    throw new Error(field + " must be a valid UUID.");
  }
  return id;
}

function requireFileName(value: unknown): string {
  const fileName = requireText(value, "fileName", 255);
  if (
    fileName === "." ||
    fileName === ".." ||
    fileName.includes("..") ||
    fileName.includes("/") ||
    fileName.includes("\\") ||
    /[\u0000-\u001f\u007f]/.test(fileName)
  ) {
    throw new Error("fileName must be a plain file name, not a path.");
  }
  return fileName;
}

function registerIpcHandlers(): void {
  ipcMain.handle("backend:status", () => backendStatus);
  ipcMain.handle("backend:restart", () => startBackend());

  ipcMain.handle("peer:start", async (_event, input: { bindAddress?: unknown; port?: unknown }) => {
    const bindAddress = input?.bindAddress === undefined
      ? "0.0.0.0"
      : requireText(input.bindAddress, "bindAddress", 253);
    const port = requirePort(input?.port, "port", true);
    const data = await command<{ peer: unknown }>("peer.start", { bindAddress, port });
    return data.peer;
  });

  ipcMain.handle("peer:stop", async () => {
    const data = await command<{ peer: unknown }>("peer.stop");
    return data.peer;
  });

  ipcMain.handle("peer:status", async () => {
    const data = await command<{ peer: unknown }>("peer.status");
    return data.peer;
  });

  ipcMain.handle("peer:list", async () => {
    const data = await command<{ peers: unknown[] }>("peer.list");
    return data.peers;
  });

  ipcMain.handle("peer:connect", async (_event, input: { host?: unknown; port?: unknown }) => {
    const host = requireText(input?.host, "host", 253);
    const port = requirePort(input?.port, "port");
    const data = await command<{ peer: unknown }>("peer.connect", { host, port });
    return data.peer;
  });

  ipcMain.handle("peer:disconnect", async (_event, peerId: unknown) => {
    const data = await command<{ peer: unknown }>("peer.disconnect", { peerId: requireUuid(peerId, "peerId") });
    return data.peer;
  });

  ipcMain.handle("security:status", async () => {
    const data = await command<{ security: unknown }>("security.status");
    return data.security;
  });

  ipcMain.handle("security:trust-peer", async (_event, fingerprint: unknown) => {
    const data = await command<{ security: unknown }>("security.trust", {
      fingerprint: requireFingerprint(fingerprint)
    });
    return data.security;
  });

  ipcMain.handle("security:untrust-peer", async (_event, fingerprint: unknown) => {
    const data = await command<{ security: unknown }>("security.untrust", {
      fingerprint: requireFingerprint(fingerprint)
    });
    return data.security;
  });

  ipcMain.handle("share:choose-directory", async () => {
    requireBackendReady();
    const options: OpenDialogOptions = {
      title: "Choose a folder to share",
      properties: ["openDirectory", "createDirectory"]
    };
    const result = mainWindow
      ? await dialog.showOpenDialog(mainWindow, options)
      : await dialog.showOpenDialog(options);
    const selectedPath = result.filePaths.at(0);
    if (result.canceled || !selectedPath) {
      return null;
    }
    const data = await command<{ sharedDirectory: unknown }>(
      "share.setDirectory",
      { path: selectedPath }
    );
    return data.sharedDirectory;
  });

  ipcMain.handle("share:get-directory", async () => {
    const data = await command<{ sharedDirectory: unknown }>("share.getDirectory");
    return data.sharedDirectory;
  });

  ipcMain.handle("share:list-local-files", async () => {
    const data = await command<{ files: unknown[] }>("files.listLocal");
    return data.files;
  });

  ipcMain.handle("files:list-remote", async (_event, peerId: unknown) => {
    const data = await command<{ files: unknown[] }>(
      "files.listRemote",
      { peerId: requireUuid(peerId, "peerId") }
    );
    return data.files;
  });

  ipcMain.handle("files:search", async (_event, keyword: unknown) => {
    const normalized = keyword === undefined || keyword === null
      ? ""
      : typeof keyword === "string" ? keyword.trim() : (() => { throw new Error("keyword must be a string."); })();
    if (normalized.length > 256) {
      throw new Error("keyword must not exceed 256 characters.");
    }
    return command("files.search", { keyword: normalized });
  });

  ipcMain.handle("transfer:download", async (_event, input: { peerId?: unknown; fileName?: unknown }) => {
    requireBackendReady();
    const peerId = requireUuid(input?.peerId, "peerId");
    const fileName = requireFileName(input?.fileName);
    const options: OpenDialogOptions = {
      title: "Choose the download folder",
      properties: ["openDirectory", "createDirectory"]
    };
    const result = mainWindow
      ? await dialog.showOpenDialog(mainWindow, options)
      : await dialog.showOpenDialog(options);
    const selectedPath = result.filePaths.at(0);
    if (result.canceled || !selectedPath) {
      return null;
    }
    const data = await command<{ download: unknown }>("transfer.download", {
      peerId,
      fileName,
      destinationDir: selectedPath
    });
    return data.download;
  });

  ipcMain.handle("transfer:download-multi", async (_event, input: { peerIds?: unknown; fileName?: unknown }) => {
    requireBackendReady();
    if (!Array.isArray(input?.peerIds) || input.peerIds.length < 1 || input.peerIds.length > 16) {
      throw new Error("peerIds must contain between 1 and 16 peer IDs.");
    }
    const peerIds = [...new Set(input.peerIds.map((value) => requireUuid(value, "peerId")))];
    const fileName = requireFileName(input?.fileName);
    const options: OpenDialogOptions = {
      title: "Choose the download folder",
      properties: ["openDirectory", "createDirectory"]
    };
    const result = mainWindow
      ? await dialog.showOpenDialog(mainWindow, options)
      : await dialog.showOpenDialog(options);
    const selectedPath = result.filePaths.at(0);
    if (result.canceled || !selectedPath) {
      return null;
    }
    const data = await command<{ download: unknown }>("transfer.downloadMulti", {
      peerIds,
      fileName,
      destinationDir: selectedPath
    });
    return data.download;
  });

  ipcMain.handle("transfer:cancel", async (_event, taskId: unknown) => {
    const data = await command<{ transfer: unknown }>(
      "transfer.cancel",
      { taskId: requireUuid(taskId, "taskId") }
    );
    return data.transfer;
  });

  ipcMain.handle("transfer:list", async () => {
    const data = await command<{ transfers: unknown[] }>("transfer.list");
    return data.transfers;
  });

  ipcMain.handle("transfer:list-resumable", async () => {
    requireBackendReady();
    const options: OpenDialogOptions = {
      title: "Choose the previous download folder",
      properties: ["openDirectory"]
    };
    const result = mainWindow
      ? await dialog.showOpenDialog(mainWindow, options)
      : await dialog.showOpenDialog(options);
    const selectedPath = result.filePaths.at(0);
    if (result.canceled || !selectedPath) {
      resumableDirectory = null;
      return null;
    }
    resumableDirectory = selectedPath;
    const data = await command<{ downloads: unknown[] }>("transfer.listResumable", {
      destinationDir: selectedPath
    });
    return data.downloads;
  });

  ipcMain.handle("internet:open-shared-file", (_event, fileName: unknown) =>
    openInternetSharedFile(fileName));

  ipcMain.handle("internet:read-shared-file-chunk", (_event, input: {
    handleId?: unknown; offset?: unknown; length?: unknown;
  }) => readInternetSharedFileChunk(input ?? {}));

  ipcMain.handle("internet:close-shared-file", (_event, handleId: unknown) =>
    closeInternetSharedFile(handleId));

  ipcMain.handle("internet:begin-receive", (_event, input: { fileName?: unknown; size?: unknown }) =>
    beginInternetReceive(input ?? {}));

  ipcMain.handle("internet:write-received-chunk", (_event, input: {
    handleId?: unknown; offset?: unknown; chunk?: unknown;
  }) => writeInternetReceiveChunk(input ?? {}));

  ipcMain.handle("internet:complete-receive", (_event, input: { handleId?: unknown; sha256?: unknown }) =>
    completeInternetReceive(input ?? {}));

  ipcMain.handle("internet:abort-receive", (_event, handleId: unknown) =>
    abortInternetReceive(handleId));

  ipcMain.handle("transfer:resume", async (_event, input: { taskId?: unknown; peerIds?: unknown }) => {
    requireBackendReady();
    if (!resumableDirectory) {
      throw new Error("Find interrupted downloads and select their folder first.");
    }
    if (!Array.isArray(input?.peerIds) || input.peerIds.length < 1 || input.peerIds.length > 16) {
      throw new Error("peerIds must contain between 1 and 16 peer IDs.");
    }
    const peerIds = [...new Set(input.peerIds.map((value) => requireUuid(value, "peerId")))];
    const taskId = requireUuid(input?.taskId, "taskId");
    const data = await command<{ download: unknown }>("transfer.resume", {
      taskId,
      peerIds,
      destinationDir: resumableDirectory
    });
    return data.download;
  });
}

function installContentSecurityPolicy(): void {
  const development = !app.isPackaged;
  const connectSources = development
    ? "connect-src 'self' http://127.0.0.1:5173 ws://127.0.0.1:5173 https:;"
    : "connect-src 'self' https:;";
  const scriptSources = development
    ? "script-src 'self' 'unsafe-eval' 'unsafe-inline';"
    : "script-src 'self';";
  const policy = [
    "default-src 'self';",
    scriptSources,
    "style-src 'self' 'unsafe-inline';",
    "img-src 'self' data:;",
    connectSources,
    "object-src 'none';",
    "base-uri 'none';",
    "frame-ancestors 'none';"
  ].join(" ");

  session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
    callback({
      responseHeaders: {
        ...details.responseHeaders,
        "Content-Security-Policy": [policy]
      }
    });
  });
}

function createWindow(): void {
  mainWindow = new BrowserWindow({
    width: 1440,
    height: 920,
    minWidth: 1080,
    minHeight: 720,
    backgroundColor: "#0b1020",
    show: false,
    webPreferences: {
      preload: join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true
    }
  });

  let windowShown = false;
  let showFallbackTimer: NodeJS.Timeout | null = null;

  const showMainWindow = (reason: string): void => {
    const window = mainWindow;
    if (!window || window.isDestroyed() || windowShown) {
      return;
    }

    windowShown = true;
    if (showFallbackTimer) {
      clearTimeout(showFallbackTimer);
      showFallbackTimer = null;
    }

    if (reason !== "ready-to-show") {
      console.warn("[Electron] Showing window via fallback:", reason);
    }
    window.show();
  };

  mainWindow.once("ready-to-show", () => showMainWindow("ready-to-show"));

  mainWindow.webContents.once("did-finish-load", () => {
    if (windowShown) {
      return;
    }

    // Some virtualized Linux desktops finish loading the renderer without
    // emitting ready-to-show. Do not leave the BrowserWindow hidden forever.
    showFallbackTimer = setTimeout(() => {
      showMainWindow("renderer-finished-loading");
    }, 2_000);
  });

  mainWindow.webContents.on(
    "did-fail-load",
    (_event, errorCode, errorDescription, validatedURL, isMainFrame) => {
      if (!isMainFrame) {
        return;
      }

      console.error("[Electron] Main frame failed to load:", {
        errorCode,
        errorDescription,
        validatedURL
      });
      showMainWindow("renderer-load-failed");
    }
  );

  mainWindow.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
  // The app is a single-page renderer; deny all renderer-initiated navigation.
  // Initial loadURL/loadFile calls below are initiated by the trusted Main process.
  mainWindow.webContents.on("will-navigate", (event) => {
    event.preventDefault();
  });

  if (!app.isPackaged && process.env.VITE_RENDERER_URL) {
    void mainWindow.loadURL(process.env.VITE_RENDERER_URL);
  } else {
    void mainWindow.loadFile(join(app.getAppPath(), "dist", "renderer", "index.html"));
  }

  mainWindow.on("closed", () => {
    if (showFallbackTimer) {
      clearTimeout(showFallbackTimer);
      showFallbackTimer = null;
    }
    mainWindow = null;
  });
}

registerIpcHandlers();

app.whenReady().then(async () => {
  installContentSecurityPolicy();
  await startBackend();
  createWindow();

  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
}).catch((error: unknown) => {
  console.error("Could not initialize the desktop application:", error);
  app.quit();
});

app.on("window-all-closed", () => {
  app.quit();
});

app.on("before-quit", (event) => {
  if (isQuitting) {
    return;
  }
  event.preventDefault();
  isQuitting = true;
  void cleanupInternetFileHandles()
    .catch((error: unknown) => console.error("[Electron] Could not clean up Internet transfers:", error))
    .finally(() => bridge.shutdown(25_000).finally(() => app.exit(0)));
});

process.on("SIGINT", () => app.quit());
process.on("SIGTERM", () => app.quit());
