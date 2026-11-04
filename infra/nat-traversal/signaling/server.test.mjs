import test from "node:test";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { createSignalingServer } from "./server.mjs";

const PEER_A = "a0a0a0a0-0000-4000-8000-000000000001";
const PEER_B = "b0b0b0b0-0000-4000-8000-000000000002";
const PEER_C = "c0c0c0c0-0000-4000-8000-000000000003";

async function withServer(options, callback) {
  const service = createSignalingServer({ host: "127.0.0.1", port: 0, ...options });
  const address = await service.listen();
  const baseUrl = "http://127.0.0.1:" + address.port;
  try {
    await callback(baseUrl);
  } finally {
    await service.close();
  }
}

async function createRoom(baseUrl, peerId = PEER_A) {
  const response = await fetch(baseUrl + "/v1/rooms", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ peerId })
  });
  assert.equal(response.status, 201);
  return response.json();
}

function roomHeaders(room, peerId) {
  return {
    authorization: "Bearer " + room.roomSecret,
    "x-peer-id": peerId
  };
}

test("creates a room with an unguessable bearer secret", async () => {
  await withServer({}, async (baseUrl) => {
    const room = await createRoom(baseUrl);
    assert.match(room.roomId, /^[A-Za-z0-9_-]{20,40}$/);
    assert.ok(room.roomSecret.length >= 40);
    assert.equal(room.peerId, PEER_A);
    assert.ok(room.expiresAt > Date.now());

    const health = await fetch(baseUrl + "/healthz");
    assert.deepEqual(await health.json(), { status: "ok", activeRooms: 1 });
  });
});

test("requires a room token and limits the room to two peers", async () => {
  await withServer({}, async (baseUrl) => {
    const room = await createRoom(baseUrl);

    const unauthorized = await fetch(baseUrl + "/v1/rooms/" + room.roomId + "/messages?wait=0", {
      headers: { "x-peer-id": PEER_A }
    });
    assert.equal(unauthorized.status, 401);

    const joined = await fetch(baseUrl + "/v1/rooms/" + room.roomId + "/join", {
      method: "POST",
      headers: {
        ...roomHeaders(room, PEER_A),
        "content-type": "application/json"
      },
      body: JSON.stringify({ peerId: PEER_B })
    });
    assert.equal(joined.status, 200);

    const full = await fetch(baseUrl + "/v1/rooms/" + room.roomId + "/join", {
      method: "POST",
      headers: {
        ...roomHeaders(room, PEER_A),
        "content-type": "application/json"
      },
      body: JSON.stringify({ peerId: PEER_C })
    });
    assert.equal(full.status, 409);
    assert.equal((await full.json()).error.code, "ROOM_FULL");
  });
});

test("delivers peer-joined and relays bounded offer messages through long polling", async () => {
  await withServer({}, async (baseUrl) => {
    const room = await createRoom(baseUrl);

    const pendingPoll = fetch(baseUrl + "/v1/rooms/" + room.roomId + "/messages?after=0&wait=5000", {
      headers: roomHeaders(room, PEER_A)
    });

    const joined = await fetch(baseUrl + "/v1/rooms/" + room.roomId + "/join", {
      method: "POST",
      headers: {
        ...roomHeaders(room, PEER_A),
        "content-type": "application/json"
      },
      body: JSON.stringify({ peerId: PEER_B })
    });
    assert.equal(joined.status, 200);

    const pollResponse = await pendingPoll;
    const pollBody = await pollResponse.json();
    assert.equal(pollBody.messages[0].type, "peer-joined");
    assert.equal(pollBody.messages[0].fromPeerId, PEER_B);

    const sent = await fetch(baseUrl + "/v1/rooms/" + room.roomId + "/messages", {
      method: "POST",
      headers: {
        ...roomHeaders(room, PEER_A),
        "content-type": "application/json"
      },
      body: JSON.stringify({
        toPeerId: PEER_B,
        type: "offer",
        payload: { sdp: "v=0\\r\\no=- example" }
      })
    });
    assert.equal(sent.status, 202);

    const received = await fetch(baseUrl + "/v1/rooms/" + room.roomId + "/messages?after=1&wait=0", {
      headers: roomHeaders(room, PEER_B)
    });
    const body = await received.json();
    assert.equal(body.messages.length, 1);
    assert.equal(body.messages[0].type, "offer");
    assert.equal(body.messages[0].payload.sdp, "v=0\\r\\no=- example");
  });
});

test("rejects messages addressed outside the room and oversized signaling payloads", async () => {
  await withServer({}, async (baseUrl) => {
    const room = await createRoom(baseUrl);
    await fetch(baseUrl + "/v1/rooms/" + room.roomId + "/join", {
      method: "POST",
      headers: {
        ...roomHeaders(room, PEER_A),
        "content-type": "application/json"
      },
      body: JSON.stringify({ peerId: PEER_B })
    });

    const invalidRecipient = await fetch(baseUrl + "/v1/rooms/" + room.roomId + "/messages", {
      method: "POST",
      headers: {
        ...roomHeaders(room, PEER_A),
        "content-type": "application/json"
      },
      body: JSON.stringify({ toPeerId: PEER_C, type: "offer", payload: {} })
    });
    assert.equal(invalidRecipient.status, 400);

    const oversized = await fetch(baseUrl + "/v1/rooms/" + room.roomId + "/messages", {
      method: "POST",
      headers: {
        ...roomHeaders(room, PEER_A),
        "content-type": "application/json"
      },
      body: JSON.stringify({
        toPeerId: PEER_B,
        type: "offer",
        payload: { sdp: "x".repeat(50 * 1024) }
      })
    });
    assert.equal(oversized.status, 413);
  });
});

test("issues short-lived TURN REST credentials without exposing the shared secret", async () => {
  const sharedSecret = "test-turn-shared-secret";
  await withServer({
    turnSharedSecret: sharedSecret,
    turnPublicHost: "turn.example.test",
    turnRealm: "example.test",
    turnPort: 3478,
    turnTlsPort: 5349,
    stunUrl: "stun:turn.example.test:3478"
  }, async (baseUrl) => {
    const room = await createRoom(baseUrl);
    await fetch(baseUrl + "/v1/rooms/" + room.roomId + "/join", {
      method: "POST",
      headers: {
        ...roomHeaders(room, PEER_A),
        "content-type": "application/json"
      },
      body: JSON.stringify({ peerId: PEER_B })
    });

    const response = await fetch(baseUrl + "/v1/rooms/" + room.roomId + "/turn-credentials", {
      method: "POST",
      headers: roomHeaders(room, PEER_B)
    });
    assert.equal(response.status, 200);
    const body = await response.json();
    const server = body.iceServers[0];
    assert.ok(server.urls.includes("turn:turn.example.test:3478?transport=udp"));
    assert.ok(server.urls.includes("turn:turn.example.test:3478?transport=tcp"));
    assert.ok(server.urls.includes("turns:turn.example.test:5349?transport=tcp"));
    assert.ok(body.expiresAt > Date.now());

    const expected = createHmac("sha1", sharedSecret).update(server.username).digest("base64");
    assert.equal(server.credential, expected);
    assert.equal(JSON.stringify(body).includes(sharedSecret), false);
  });
});

test("reports TURN as unavailable when no shared secret is configured", async () => {
  await withServer({}, async (baseUrl) => {
    const room = await createRoom(baseUrl);
    const response = await fetch(baseUrl + "/v1/rooms/" + room.roomId + "/turn-credentials", {
      method: "POST",
      headers: roomHeaders(room, PEER_A)
    });
    assert.equal(response.status, 503);
    assert.equal((await response.json()).error.code, "TURN_NOT_CONFIGURED");
  });
});

test("expires rendezvous rooms", async () => {
  let clock = Date.now();
  await withServer({ now: () => clock, roomTtlMs: 1000 }, async (baseUrl) => {
    const room = await createRoom(baseUrl);
    clock += 1001;
    const response = await fetch(baseUrl + "/v1/rooms/" + room.roomId + "/messages?wait=0", {
      headers: roomHeaders(room, PEER_A)
    });
    assert.equal(response.status, 404);
    assert.equal((await response.json()).error.code, "ROOM_NOT_FOUND");
  });
});
