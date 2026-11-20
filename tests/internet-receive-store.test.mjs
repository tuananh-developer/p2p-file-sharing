import test from "node:test";
import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { appendFile, mkdtemp, mkdir, readFile, readdir, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { InternetReceiveStore } = require("../dist/electron/InternetReceiveStore.js");

function identity(data, overrides = {}) {
  return {
    transferId: "123e4567-e89b-42d3-a456-426614174000",
    fileName: "payload.bin",
    size: data.length,
    sha256: createHash("sha256").update(data).digest("hex"),
    ...overrides
  };
}

async function withStore(callback) {
  const root = await mkdtemp(join(tmpdir(), "p2p-internet-resume-"));
  const storage = join(root, "resume");
  const destination = join(root, "destination");
  await mkdir(destination, { recursive: true });
  try {
    await callback({ root, storage, destination });
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}

test("resumes a partial receive after a fresh store instance and verifies the final file", async () => {
  await withStore(async ({ storage, destination }) => {
    const data = Buffer.alloc(80 * 1024, 0x5a);
    const file = identity(data);
    const first = new InternetReceiveStore(storage);
    const started = await first.begin({
      ...file,
      targetPath: join(destination, file.fileName)
    });
    assert.equal(started.offset, 0);

    const checkpoint = 48 * 1024;
    const nextOffset = await first.write({
      handleId: started.handleId,
      offset: 0,
      chunk: data.subarray(0, checkpoint)
    });
    assert.equal(nextOffset, checkpoint);
    await first.suspend(started.handleId);

    const afterRestart = new InternetReceiveStore(storage);
    assert.equal(await afterRestart.inspect(file), checkpoint);
    const resumed = await afterRestart.begin({
      ...file,
      targetPath: join(destination, file.fileName)
    });
    assert.equal(resumed.offset, checkpoint);
    await afterRestart.write({
      handleId: resumed.handleId,
      offset: checkpoint,
      chunk: data.subarray(checkpoint)
    });

    const completed = await afterRestart.complete(resumed.handleId, file.sha256);
    assert.equal(completed.size, data.length);
    assert.deepEqual(await readFile(join(destination, file.fileName)), data);
    assert.deepEqual(await readdir(storage), []);
  });
});

test("truncates uncheckpointed bytes after a crash and resumes from the durable manifest offset", async () => {
  await withStore(async ({ storage, destination }) => {
    const data = Buffer.alloc(96 * 1024, 0x31);
    const file = identity(data);
    const first = new InternetReceiveStore(storage);
    const started = await first.begin({ ...file, targetPath: join(destination, file.fileName) });
    const checkpoint = 48 * 1024;
    await first.write({
      handleId: started.handleId,
      offset: 0,
      chunk: data.subarray(0, checkpoint)
    });
    await first.suspend(started.handleId);

    await appendFile(join(storage, file.transferId + ".part"), Buffer.from("uncommitted-tail"));
    const resumedStore = new InternetReceiveStore(storage);
    const resumed = await resumedStore.begin({ ...file, targetPath: join(destination, file.fileName) });
    assert.equal(resumed.offset, checkpoint);
    assert.equal((await stat(join(storage, file.transferId + ".part"))).size, checkpoint);
    await resumedStore.abort(resumed.handleId);
  });
});

test("refuses to mix a partial transfer with different file metadata", async () => {
  await withStore(async ({ storage, destination }) => {
    const data = Buffer.alloc(48 * 1024, 0x11);
    const file = identity(data);
    const first = new InternetReceiveStore(storage);
    const started = await first.begin({ ...file, targetPath: join(destination, file.fileName) });
    await first.write({ handleId: started.handleId, offset: 0, chunk: data });
    await first.suspend(started.handleId);

    const changed = { ...file, fileName: "different.bin" };
    const resumedStore = new InternetReceiveStore(storage);
    await assert.rejects(
      resumedStore.begin({ ...changed, targetPath: join(destination, changed.fileName) }),
      /different file metadata/
    );
  });
});

test("rejects non-sequential chunks and deletes the checkpoint on explicit abort", async () => {
  await withStore(async ({ storage, destination }) => {
    const data = Buffer.alloc(48 * 1024, 0x22);
    const file = identity(data);
    const store = new InternetReceiveStore(storage);
    const started = await store.begin({ ...file, targetPath: join(destination, file.fileName) });

    await assert.rejects(
      store.write({ handleId: started.handleId, offset: 1, chunk: data.subarray(0, 10) }),
      /sequential/
    );
    await store.abort(started.handleId);
    assert.deepEqual(await readdir(storage), []);
  });
});

test("does not overwrite an existing destination and keeps the completed checkpoint resumable", async () => {
  await withStore(async ({ storage, destination }) => {
    const data = Buffer.from("preserve existing destination");
    const file = identity(data);
    const existingPath = join(destination, file.fileName);
    await import("node:fs/promises").then(({ writeFile }) => writeFile(existingPath, "existing"));
    const first = new InternetReceiveStore(storage);
    const started = await first.begin({ ...file, targetPath: existingPath });
    await first.write({ handleId: started.handleId, offset: 0, chunk: data });

    await assert.rejects(first.complete(started.handleId, file.sha256), (error) => error.code === "EEXIST");
    assert.equal(await readFile(existingPath, "utf8"), "existing");

    const second = new InternetReceiveStore(storage);
    const alternativePath = join(destination, "payload-recovered.bin");
    const resumed = await second.begin({ ...file, targetPath: alternativePath });
    assert.equal(resumed.offset, data.length);
    await second.complete(resumed.handleId, file.sha256);
    assert.deepEqual(await readFile(alternativePath), data);
  });
});

test("rejects invalid transfer identifiers before creating storage", async () => {
  await withStore(async ({ storage, destination }) => {
    const data = Buffer.from("x");
    const file = identity(data, { transferId: "not-a-uuid" });
    const store = new InternetReceiveStore(storage);
    await assert.rejects(
      store.begin({ ...file, targetPath: join(destination, file.fileName) }),
      /valid UUID/
    );
    await assert.rejects(readdir(storage), (error) => error.code === "ENOENT");
  });
});
