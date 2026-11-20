import { constants as fsConstants } from "node:fs";
import {
  chmod,
  lstat,
  link,
  mkdir,
  open,
  rename,
  unlink,
  type FileHandle
} from "node:fs/promises";
import { basename, dirname, isAbsolute, join, resolve } from "node:path";
import { createHash, randomUUID, type Hash } from "node:crypto";

const FORMAT_VERSION = 1;
const MAX_FILE_BYTES = 50 * 1024 * 1024 * 1024;
const MAX_CHUNK_BYTES = 48 * 1024;
const MAX_MANIFEST_BYTES = 16 * 1024;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const SHA256_PATTERN = /^[0-9a-f]{64}$/i;

export interface InternetReceiveIdentity {
  transferId: string;
  fileName: string;
  size: number;
  sha256: string;
}

export interface InternetReceiveStart extends InternetReceiveIdentity {
  targetPath: string;
}

export interface InternetReceiveHandle {
  handleId: string;
  fileName: string;
  size: number;
  offset: number;
}

interface ResumeManifest extends InternetReceiveIdentity {
  version: number;
  receivedBytes: number;
}

interface ActiveReceive extends InternetReceiveIdentity {
  handleId: string;
  targetPath: string;
  partPath: string;
  manifestPath: string;
  handle: FileHandle;
  nextOffset: number;
  hash: Hash;
}

function validateIdentity(input: InternetReceiveIdentity): InternetReceiveIdentity {
  if (!UUID_PATTERN.test(input.transferId)) {
    throw new Error("transferId must be a valid UUID.");
  }
  if (
    typeof input.fileName !== "string" ||
    input.fileName.trim().length === 0 ||
    input.fileName.length > 255 ||
    input.fileName === "." ||
    input.fileName === ".." ||
    input.fileName.includes("..") ||
    input.fileName.includes("/") ||
    input.fileName.includes("\\") ||
    /[\u0000-\u001f\u007f]/.test(input.fileName)
  ) {
    throw new Error("fileName must be a plain file name, not a path.");
  }
  if (!Number.isSafeInteger(input.size) || input.size < 0 || input.size > MAX_FILE_BYTES) {
    throw new Error("The incoming file size is invalid or exceeds the 50 GiB transfer limit.");
  }
  if (!SHA256_PATTERN.test(input.sha256)) {
    throw new Error("sha256 must be a 64-character hexadecimal digest.");
  }
  return { ...input, sha256: input.sha256.toLowerCase() };
}

function isNotFound(error: unknown): boolean {
  return (error as NodeJS.ErrnoException | undefined)?.code === "ENOENT";
}

function hashBufferPrefix(handle: FileHandle, length: number): Promise<Hash> {
  const digest = createHash("sha256");
  return (async () => {
    const buffer = Buffer.allocUnsafe(256 * 1024);
    let offset = 0;
    while (offset < length) {
      const requested = Math.min(buffer.length, length - offset);
      const { bytesRead } = await handle.read(buffer, 0, requested, offset);
      if (bytesRead <= 0) {
        throw new Error("The saved partial transfer ended before its checkpoint.");
      }
      digest.update(buffer.subarray(0, bytesRead));
      offset += bytesRead;
    }
    return digest;
  })();
}

/**
 * Crash-safe staging for WebRTC receives.
 *
 * Every acknowledged chunk is fsynced before the resume manifest is advanced.
 * On reopen, bytes beyond the manifest checkpoint are truncated and the SHA-256
 * state is rebuilt from the durable prefix. Partial files stay in the private
 * Electron user-data directory; the final file is copied beside the chosen
 * destination and published with an exclusive hard link (never overwrite).
 */
export class InternetReceiveStore {
  private readonly active = new Map<string, ActiveReceive>();
  private readonly activeTransfers = new Map<string, string>();
  private ready: Promise<void> | null = null;

  constructor(private readonly directory: string) {
    if (!isAbsolute(directory)) {
      throw new Error("Internet receive storage directory must be absolute.");
    }
  }

  private async ensureDirectory(): Promise<void> {
    if (!this.ready) {
      this.ready = (async () => {
        await mkdir(this.directory, { recursive: true, mode: 0o700 });
        const info = await lstat(this.directory);
        if (info.isSymbolicLink() || !info.isDirectory()) {
          throw new Error("Internet receive storage is not a private directory.");
        }
        await chmod(this.directory, 0o700);
      })();
    }
    await this.ready;
  }

  private paths(transferId: string): { partPath: string; manifestPath: string } {
    return {
      partPath: join(this.directory, transferId + ".part"),
      manifestPath: join(this.directory, transferId + ".json")
    };
  }

  private async readManifest(path: string): Promise<ResumeManifest | null> {
    let info;
    try {
      info = await lstat(path);
    } catch (error) {
      if (isNotFound(error)) return null;
      throw error;
    }
    if (info.isSymbolicLink() || !info.isFile() || info.size > MAX_MANIFEST_BYTES) {
      throw new Error("The saved Internet-transfer manifest is not a valid regular file.");
    }

    const noFollow = typeof fsConstants.O_NOFOLLOW === "number" ? fsConstants.O_NOFOLLOW : 0;
    const handle = await open(path, fsConstants.O_RDONLY | noFollow);
    try {
      const opened = await handle.stat();
      if (!opened.isFile() || opened.dev !== info.dev || opened.ino !== info.ino) {
        throw new Error("The saved Internet-transfer manifest changed while it was opened.");
      }
      const value = JSON.parse(await handle.readFile("utf8")) as Partial<ResumeManifest>;
      if (
        value.version !== FORMAT_VERSION ||
        typeof value.transferId !== "string" ||
        !UUID_PATTERN.test(value.transferId) ||
        typeof value.fileName !== "string" ||
        typeof value.size !== "number" ||
        !Number.isSafeInteger(value.size) ||
        value.size < 0 ||
        value.size > MAX_FILE_BYTES ||
        typeof value.sha256 !== "string" ||
        !SHA256_PATTERN.test(value.sha256) ||
        typeof value.receivedBytes !== "number" ||
        !Number.isSafeInteger(value.receivedBytes) ||
        value.receivedBytes < 0 ||
        value.receivedBytes > value.size
      ) {
        throw new Error("The saved Internet-transfer manifest is malformed.");
      }
      const identity = validateIdentity({
        transferId: value.transferId,
        fileName: value.fileName,
        size: value.size,
        sha256: value.sha256
      });
      return {
        version: FORMAT_VERSION,
        ...identity,
        receivedBytes: value.receivedBytes
      };
    } finally {
      await handle.close();
    }
  }

  private async saveManifest(path: string, manifest: ResumeManifest): Promise<void> {
    const temporaryPath = path + "." + randomUUID() + ".tmp";
    const handle = await open(temporaryPath, "wx", 0o600);
    try {
      await handle.writeFile(JSON.stringify(manifest), "utf8");
      await handle.sync();
    } finally {
      await handle.close();
    }
    try {
      await rename(temporaryPath, path);
    } catch (error) {
      await unlink(temporaryPath).catch(() => undefined);
      throw error;
    }
  }

  private assertManifestMatches(
    manifest: ResumeManifest,
    identity: InternetReceiveIdentity
  ): void {
    if (
      manifest.transferId !== identity.transferId ||
      manifest.fileName !== identity.fileName ||
      manifest.size !== identity.size ||
      manifest.sha256 !== identity.sha256
    ) {
      throw new Error("A saved partial transfer with this ID has different file metadata; refusing to mix data.");
    }
  }

  async inspect(input: InternetReceiveIdentity): Promise<number> {
    const identity = validateIdentity(input);
    await this.ensureDirectory();
    const { partPath, manifestPath } = this.paths(identity.transferId);
    const manifest = await this.readManifest(manifestPath);
    if (!manifest) return 0;
    this.assertManifestMatches(manifest, identity);

    const info = await lstat(partPath);
    if (info.isSymbolicLink() || !info.isFile() || info.size < manifest.receivedBytes) {
      throw new Error("The saved partial Internet transfer is missing or invalid.");
    }
    return manifest.receivedBytes;
  }

  async begin(input: InternetReceiveStart): Promise<InternetReceiveHandle> {
    const identity = validateIdentity(input);
    const targetPath = resolve(input.targetPath);
    if (!isAbsolute(targetPath) || dirname(targetPath) === targetPath) {
      throw new Error("A valid destination file path is required.");
    }
    await this.ensureDirectory();

    if (this.activeTransfers.has(identity.transferId)) {
      throw new Error("This Internet transfer is already active in this application.");
    }

    const { partPath, manifestPath } = this.paths(identity.transferId);
    let manifest = await this.readManifest(manifestPath);
    let handle: FileHandle;

    if (manifest) {
      this.assertManifestMatches(manifest, identity);
      const info = await lstat(partPath);
      if (info.isSymbolicLink() || !info.isFile()) {
        throw new Error("The saved partial Internet transfer is not a regular file.");
      }
      const noFollow = typeof fsConstants.O_NOFOLLOW === "number" ? fsConstants.O_NOFOLLOW : 0;
      handle = await open(partPath, fsConstants.O_RDWR | noFollow);
      const opened = await handle.stat();
      if (!opened.isFile() || opened.dev !== info.dev || opened.ino !== info.ino) {
        await handle.close();
        throw new Error("The saved partial Internet transfer changed while it was opened.");
      }
      if (opened.size < manifest.receivedBytes) {
        await handle.close();
        throw new Error("The saved partial Internet transfer is shorter than its durable checkpoint.");
      }
      await handle.truncate(manifest.receivedBytes);
    } else {
      try {
        const orphan = await lstat(partPath);
        if (orphan.isSymbolicLink() || !orphan.isFile()) {
          throw new Error("An invalid file occupies the Internet-transfer staging path.");
        }
        await unlink(partPath);
      } catch (error) {
        if (!isNotFound(error)) throw error;
      }
      handle = await open(partPath, "wx+", 0o600);
      manifest = {
        version: FORMAT_VERSION,
        ...identity,
        receivedBytes: 0
      };
      try {
        await this.saveManifest(manifestPath, manifest);
      } catch (error) {
        await handle.close().catch(() => undefined);
        await unlink(partPath).catch(() => undefined);
        throw error;
      }
    }

    const offset = manifest?.receivedBytes ?? 0;
    let hash: Hash;
    try {
      hash = await hashBufferPrefix(handle, offset);
    } catch (error) {
      await handle.close().catch(() => undefined);
      throw error;
    }

    const handleId = randomUUID();
    this.active.set(handleId, {
      ...identity,
      handleId,
      targetPath,
      partPath,
      manifestPath,
      handle,
      nextOffset: offset,
      hash
    });
    this.activeTransfers.set(identity.transferId, handleId);
    return {
      handleId,
      fileName: basename(targetPath),
      size: identity.size,
      offset
    };
  }

  async write(input: {
    handleId: string;
    offset: number;
    chunk: Uint8Array;
  }): Promise<number> {
    const receive = this.active.get(input.handleId);
    if (!receive) throw new Error("The incoming file handle is no longer available.");
    if (
      !(input.chunk instanceof Uint8Array) ||
      input.chunk.byteLength < 1 ||
      input.chunk.byteLength > MAX_CHUNK_BYTES
    ) {
      throw new Error("An incoming chunk must contain between 1 byte and 48 KiB.");
    }
    if (
      !Number.isSafeInteger(input.offset) ||
      input.offset !== receive.nextOffset ||
      input.offset + input.chunk.byteLength > receive.size
    ) {
      throw new Error("Incoming file chunks must be sequential and within the advertised file size.");
    }

    const buffer = Buffer.from(input.chunk);
    const previousOffset = receive.nextOffset;
    let written = 0;
    try {
      while (written < buffer.length) {
        const result = await receive.handle.write(
          buffer,
          written,
          buffer.length - written,
          previousOffset + written
        );
        if (result.bytesWritten <= 0) throw new Error("Could not write the incoming file chunk.");
        written += result.bytesWritten;
      }
      await receive.handle.sync();
      const nextOffset = previousOffset + buffer.length;
      await this.saveManifest(receive.manifestPath, {
        version: FORMAT_VERSION,
        transferId: receive.transferId,
        fileName: receive.fileName,
        size: receive.size,
        sha256: receive.sha256,
        receivedBytes: nextOffset
      });
      receive.hash.update(buffer);
      receive.nextOffset = nextOffset;
      return nextOffset;
    } catch (error) {
      await receive.handle.truncate(previousOffset).catch(() => undefined);
      await receive.handle.sync().catch(() => undefined);
      throw error;
    }
  }

  async suspend(handleId: string): Promise<void> {
    const receive = this.active.get(handleId);
    if (!receive) return;
    this.active.delete(handleId);
    this.activeTransfers.delete(receive.transferId);
    await receive.handle.sync().catch(() => undefined);
    await receive.handle.close().catch(() => undefined);
  }

  async suspendAll(): Promise<void> {
    await Promise.all([...this.active.keys()].map((handleId) => this.suspend(handleId)));
  }

  async abort(handleId: string): Promise<void> {
    const receive = this.active.get(handleId);
    if (!receive) return;
    this.active.delete(handleId);
    this.activeTransfers.delete(receive.transferId);
    await receive.handle.close().catch(() => undefined);
    await unlink(receive.partPath).catch(() => undefined);
    await unlink(receive.manifestPath).catch(() => undefined);
  }

  async complete(handleId: string, expectedHash: string): Promise<{
    fileName: string;
    size: number;
    sha256: string;
  }> {
    const receive = this.active.get(handleId);
    if (!receive) throw new Error("The incoming file handle is no longer available.");
    const normalizedHash = expectedHash.toLowerCase();
    if (!SHA256_PATTERN.test(normalizedHash) || normalizedHash !== receive.sha256) {
      throw new Error("The expected SHA-256 does not match the accepted file offer.");
    }
    if (receive.nextOffset !== receive.size) {
      throw new Error("The incoming file is incomplete; refusing to finalize it.");
    }
    const actualHash = receive.hash.digest("hex");
    if (actualHash !== normalizedHash) {
      await this.abort(handleId);
      throw new Error("The received file failed SHA-256 verification.");
    }

    await receive.handle.sync();
    await receive.handle.close();
    this.active.delete(handleId);
    this.activeTransfers.delete(receive.transferId);

    const temporaryPath = join(
      dirname(receive.targetPath),
      "." + basename(receive.targetPath) + "." + receive.transferId + "." + randomUUID() + ".part"
    );
    let source: FileHandle | null = null;
    let destination: FileHandle | null = null;
    try {
      source = await open(receive.partPath, fsConstants.O_RDONLY);
      destination = await open(temporaryPath, "wx", 0o600);
      const buffer = Buffer.allocUnsafe(1024 * 1024);
      let offset = 0;
      while (offset < receive.size) {
        const requested = Math.min(buffer.length, receive.size - offset);
        const { bytesRead } = await source.read(buffer, 0, requested, offset);
        if (bytesRead <= 0) throw new Error("The staged Internet transfer ended before its verified size.");
        let written = 0;
        while (written < bytesRead) {
          const result = await destination.write(buffer, written, bytesRead - written, offset + written);
          if (result.bytesWritten <= 0) throw new Error("Could not publish the received file.");
          written += result.bytesWritten;
        }
        offset += bytesRead;
      }
      await destination.sync();
      await destination.close();
      destination = null;
      await source.close();
      source = null;

      // link() fails with EEXIST, so a file created after the save dialog is never overwritten.
      await link(temporaryPath, receive.targetPath);
      await unlink(temporaryPath).catch(() => undefined);
      await unlink(receive.partPath).catch(() => undefined);
      await unlink(receive.manifestPath).catch(() => undefined);
      return {
        fileName: basename(receive.targetPath),
        size: receive.size,
        sha256: actualHash
      };
    } catch (error) {
      await source?.close().catch(() => undefined);
      await destination?.close().catch(() => undefined);
      await unlink(temporaryPath).catch(() => undefined);
      // Keep the durable partial and manifest so the user can retry with another destination.
      throw error;
    }
  }
}
