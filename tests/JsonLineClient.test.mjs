import assert from "node:assert/strict";
import { once } from "node:events";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { JsonLineClient } from "../dist/electron/backend/JsonLineClient.js";

const currentDirectory = dirname(fileURLToPath(import.meta.url));
const fixture = resolve(currentDirectory, "fixtures", "fake-java-bridge.mjs");

async function createClient() {
  const client = new JsonLineClient();
  await client.start(process.execPath, [fixture], { startupTimeoutMs: 2_000 });
  return client;
}

test("waits for READY and correlates JSON Lines responses by request id", async (context) => {
  const client = await createClient();
  context.after(() => client.shutdown());

  const eventPromise = once(client, "event");
  const [first, second] = await Promise.all([
    client.request("test.echo", { value: "first" }),
    client.request("test.echo", { value: "second" })
  ]);

  assert.deepEqual(first, { echo: { value: "first" } });
  assert.deepEqual(second, { echo: { value: "second" } });

  const [event] = await eventPromise;
  assert.equal(event.event, "activityLogged");
  assert.match(event.data.message, /test\.echo/);
});

test("rejects an expired request without leaving a pending correlation entry", async (context) => {
  const client = await createClient();
  context.after(() => client.shutdown());

  await assert.rejects(
    client.request("test.hang", {}, 60),
    /request timed out/
  );
  assert.equal(client.getState(), "READY");
});

test("rejects pending requests when the backend exits unexpectedly", async () => {
  const client = await createClient();
  const pending = client.request("test.exit", {}, 2_000);

  await assert.rejects(pending, /exited unexpectedly/);
  assert.equal(client.getState(), "FAILED");
  await client.shutdown();
});

test("requests a graceful app shutdown and waits for the child to exit", async () => {
  const client = await createClient();

  await client.shutdown(1_000);

  assert.equal(client.getState(), "STOPPED");
});

test("marks malformed stdout as a transport failure", async () => {
  const client = await createClient();
  const failure = once(client, "transportError");
  const pending = client.request("test.invalid", {}, 2_000);
  const [message] = await failure;
  assert.match(message, /invalid JSON/);
  await assert.rejects(pending, /invalid JSON/);
  assert.equal(client.getState(), "FAILED");
  await client.shutdown();
});
