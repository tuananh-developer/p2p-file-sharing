import { createServer } from "node:http";
import {
  createHash,
  createHmac,
  randomBytes,
  timingSafeEqual
} from "node:crypto";
import { pathToFileURL } from "node:url";

const PEER_ID_PATTERN = /^[0-9a-f-]{36}$/i;
const MESSAGE_TYPES = new Set(["offer", "answer", "ice-candidate", "hangup"]);
const MAX_BODY_BYTES = 64 * 1024;
const MAX_PAYLOAD_BYTES = 48 * 1024;
const MAX_ROOMS = 10_000;
const MAX_MESSAGES_PER_ROOM = 512;
const DEFAULT_ROOM_TTL_MS = 10 * 60 * 1000;
const MAX_LONG_POLL_MS = 20_000;

class HttpError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

function digestSecret(secret) {
  return createHash("sha256").update(secret, "utf8").digest();
}

function safeEqual(left, right) {
  const a = Buffer.isBuffer(left) ? left : Buffer.from(left);
  const b = Buffer.isBuffer(right) ? right : Buffer.from(right);
  return a.length === b.length && timingSafeEqual(a, b);
}

function json(response, status, value, origin = "*") {
  if (response.writableEnded || response.destroyed) return;
  response.writeHead(status, {
    "access-control-allow-origin": origin,
    "access-control-allow-headers": "authorization, content-type, x-peer-id",
    "access-control-allow-methods": "GET, POST, OPTIONS",
    "cache-control": "no-store",
    "content-type": "application/json; charset=utf-8",
    "x-content-type-options": "nosniff"
  });
  response.end(JSON.stringify(value));
}

async function readJson(request) {
  const contentType = request.headers["content-type"] ?? "";
  if (!contentType.toLowerCase().startsWith("application/json")) {
    throw new HttpError(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json.");
  }

  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) {
      throw new HttpError(413, "BODY_TOO_LARGE", "Request body exceeds the 64 KiB limit.");
    }
    chunks.push(chunk);
  }

  if (size === 0) throw new HttpError(400, "EMPTY_BODY", "A JSON request body is required.");

  try {
    const parsed = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      throw new Error("Expected a JSON object.");
    }
    return parsed;
  } catch {
    throw new HttpError(400, "INVALID_JSON", "Request body must be a valid JSON object.");
  }
}

function validatePeerId(value) {
  if (typeof value !== "string" || !PEER_ID_PATTERN.test(value)) {
    throw new HttpError(400, "INVALID_PEER_ID", "peerId must be a UUID.");
  }
  return value.toLowerCase();
}

function getBearerToken(request) {
  const header = request.headers.authorization ?? "";
  const match = /^Bearer ([A-Za-z0-9_-]{40,64})$/.exec(header);
  if (!match) throw new HttpError(401, "UNAUTHORIZED", "A valid room bearer token is required.");
  return match[1];
}

function createRoomRecord(id, secret, peerId, now, ttlMs) {
  return {
    id,
    secretHash: digestSecret(secret),
    createdAt: now,
    expiresAt: now + ttlMs,
    members: new Map([[peerId, { peerId, joinedAt: now }]]),
    messages: [],
    nextSequence: 1,
    pollers: new Map()
  };
}

function collectMessages(room, peerId, after) {
  return room.messages.filter((message) =>
    message.sequence > after && message.toPeerId === peerId
  );
}

export function createSignalingServer(options = {}) {
  const host = options.host ?? process.env.HOST ?? "0.0.0.0";
  const port = options.port ?? Number(process.env.PORT ?? 8080);
  const roomTtlMs = options.roomTtlMs ?? DEFAULT_ROOM_TTL_MS;
  const now = options.now ?? Date.now;
  const turnSharedSecret = options.turnSharedSecret ?? process.env.TURN_SHARED_SECRET ?? "";
  const turnPublicHost = options.turnPublicHost ?? process.env.TURN_PUBLIC_HOST ?? "";
  const turnRealm = options.turnRealm ?? process.env.TURN_REALM ?? turnPublicHost;
  const turnPort = options.turnPort ?? Number(process.env.TURN_PORT ?? 3478);
  const turnTlsPort = options.turnTlsPort ?? Number(process.env.TURN_TLS_PORT ?? 5349);
  const stunUrl = options.stunUrl ?? process.env.STUN_URL ?? "";
  const rooms = new Map();

  function closePoller(room, peerId, status, body) {
    const poller = room.pollers.get(peerId);
    if (!poller) return;
    room.pollers.delete(peerId);
    clearTimeout(poller.timer);
    if (!poller.response.writableEnded && !poller.response.destroyed) {
      json(poller.response, status, body, poller.origin);
    }
  }

  function removeRoom(room, status = 410) {
    if (!rooms.delete(room.id)) return;
    for (const peerId of room.pollers.keys()) {
      closePoller(room, peerId, status, {
        error: { code: "ROOM_EXPIRED", message: "This rendezvous room has expired." }
      });
    }
  }

  function expireRooms() {
    const timestamp = now();
    for (const room of rooms.values()) {
      if (room.expiresAt <= timestamp) removeRoom(room);
    }
  }

  function findRoom(roomId) {
    expireRooms();
    const room = rooms.get(roomId);
    if (!room) {
      throw new HttpError(404, "ROOM_NOT_FOUND", "Rendezvous room does not exist or has expired.");
    }
    return room;
  }

  function authorizeRoom(request, room, peerId) {
    const suppliedHash = digestSecret(getBearerToken(request));
    if (!safeEqual(suppliedHash, room.secretHash)) {
      throw new HttpError(401, "UNAUTHORIZED", "Room token is invalid.");
    }
    if (!room.members.has(peerId)) {
      throw new HttpError(403, "NOT_A_ROOM_MEMBER", "This peer has not joined the rendezvous room.");
    }
  }

  function appendMessage(room, message) {
    const stored = { sequence: room.nextSequence++, sentAt: now(), ...message };
    room.messages.push(stored);
    if (room.messages.length > MAX_MESSAGES_PER_ROOM) {
      room.messages.splice(0, room.messages.length - MAX_MESSAGES_PER_ROOM);
    }

    const poller = room.pollers.get(stored.toPeerId);
    if (poller && collectMessages(room, stored.toPeerId, poller.after).length > 0) {
      const messages = collectMessages(room, stored.toPeerId, poller.after);
      closePoller(room, stored.toPeerId, 200, {
        messages,
        cursor: messages[messages.length - 1].sequence,
        expiresAt: room.expiresAt
      });
    }
    return stored;
  }

  function handlePoll(response, room, peerId, url, origin) {
    const afterValue = url.searchParams.get("after") ?? "0";
    const waitValue = url.searchParams.get("wait") ?? "20000";
    if (!/^\d+$/.test(afterValue) || !/^\d+$/.test(waitValue)) {
      throw new HttpError(400, "INVALID_CURSOR", "after and wait must be non-negative integers.");
    }

    const after = Number(afterValue);
    const waitMs = Math.min(MAX_LONG_POLL_MS, Number(waitValue));
    if (!Number.isSafeInteger(after) || !Number.isSafeInteger(waitMs)) {
      throw new HttpError(400, "INVALID_CURSOR", "Polling cursor or wait duration is out of range.");
    }

    const messages = collectMessages(room, peerId, after);
    if (messages.length > 0 || waitMs === 0) {
      json(response, 200, {
        messages,
        cursor: messages.length ? messages[messages.length - 1].sequence : after,
        expiresAt: room.expiresAt
      }, origin);
      return;
    }

    if (room.pollers.has(peerId)) {
      throw new HttpError(409, "POLL_ALREADY_ACTIVE", "Only one long-poll request per peer is allowed.");
    }

    const poller = {
      after,
      response,
      origin,
      timer: setTimeout(() => {
        const pending = collectMessages(room, peerId, after);
        json(response, 200, {
          messages: pending,
          cursor: pending.length ? pending[pending.length - 1].sequence : after,
          expiresAt: room.expiresAt
        }, origin);
        room.pollers.delete(peerId);
      }, waitMs)
    };
    poller.timer.unref?.();
    room.pollers.set(peerId, poller);
    response.on("close", () => {
      if (room.pollers.get(peerId) === poller) {
        room.pollers.delete(peerId);
        clearTimeout(poller.timer);
      }
    });
  }

  function getTurnCredentials(peerId) {
    if (!turnSharedSecret || !turnPublicHost) {
      throw new HttpError(503, "TURN_NOT_CONFIGURED", "TURN credentials are not configured on this server.");
    }

    const expiresAt = Math.floor(now() / 1000) + 10 * 60;
    const username = expiresAt + ":" + peerId;
    const credential = createHmac("sha1", turnSharedSecret)
      .update(username, "utf8")
      .digest("base64");
    const urls = [
      "turn:" + turnPublicHost + ":" + turnPort + "?transport=udp",
      "turn:" + turnPublicHost + ":" + turnPort + "?transport=tcp"
    ];
    if (turnTlsPort > 0) {
      urls.push("turns:" + turnPublicHost + ":" + turnTlsPort + "?transport=tcp");
    }
    if (stunUrl) urls.unshift(stunUrl);

    return {
      iceServers: [{ urls, username, credential, credentialType: "password" }],
      realm: turnRealm || turnPublicHost,
      expiresAt: expiresAt * 1000
    };
  }

  const server = createServer(async (request, response) => {
    const originHeader = request.headers.origin ?? "";
    const origin = options.allowedOrigins?.length
      ? (options.allowedOrigins.includes(originHeader) ? originHeader : "null")
      : "*";
    response.setHeader("access-control-allow-origin", origin);
    response.setHeader("vary", "origin");

    if (request.method === "OPTIONS") {
      response.writeHead(204, {
        "access-control-allow-origin": origin,
        "access-control-allow-headers": "authorization, content-type, x-peer-id",
        "access-control-allow-methods": "GET, POST, OPTIONS",
        "access-control-max-age": "600"
      });
      response.end();
      return;
    }

    try {
      const url = new URL(request.url ?? "/", "http://signaling.local");
      if (request.method === "GET" && url.pathname === "/healthz") {
        json(response, 200, { status: "ok", activeRooms: rooms.size }, origin);
        return;
      }

      if (request.method === "POST" && url.pathname === "/v1/rooms") {
        const body = await readJson(request);
        const peerId = validatePeerId(body.peerId);
        expireRooms();
        if (rooms.size >= MAX_ROOMS) {
          throw new HttpError(503, "ROOM_CAPACITY_REACHED", "The signaling service is at capacity.");
        }

        const roomId = randomBytes(18).toString("base64url");
        const secret = randomBytes(32).toString("base64url");
        const room = createRoomRecord(roomId, secret, peerId, now(), roomTtlMs);
        rooms.set(roomId, room);
        json(response, 201, {
          roomId,
          roomSecret: secret,
          peerId,
          expiresAt: room.expiresAt
        }, origin);
        return;
      }

      const match = /^\/v1\/rooms\/([A-Za-z0-9_-]{20,40})\/(join|messages|leave|turn-credentials)$/.exec(url.pathname);
      if (!match) throw new HttpError(404, "NOT_FOUND", "Endpoint does not exist.");

      const room = findRoom(match[1]);
      const action = match[2];

      if (action === "join" && request.method === "POST") {
        const body = await readJson(request);
        const peerId = validatePeerId(body.peerId);
        const hostPeerId = [...room.members.keys()][0];
        const suppliedHash = digestSecret(getBearerToken(request));
        if (!safeEqual(suppliedHash, room.secretHash)) {
          throw new HttpError(401, "UNAUTHORIZED", "Room token is invalid.");
        }
        if (room.members.has(peerId)) {
          throw new HttpError(409, "PEER_ALREADY_JOINED", "This peer is already a room member.");
        }
        if (room.members.size >= 2) {
          throw new HttpError(409, "ROOM_FULL", "Rendezvous rooms support exactly two peers.");
        }
        room.members.set(peerId, { peerId, joinedAt: now() });
        appendMessage(room, {
          fromPeerId: peerId,
          toPeerId: hostPeerId,
          type: "peer-joined",
          payload: {}
        });
        json(response, 200, {
          roomId: room.id,
          peerId,
          otherPeerId: hostPeerId,
          expiresAt: room.expiresAt
        }, origin);
        return;
      }

      if (action === "join") throw new HttpError(405, "METHOD_NOT_ALLOWED", "Use POST to join a room.");

      const peerId = validatePeerId(request.headers["x-peer-id"]);
      authorizeRoom(request, room, peerId);

      if (action === "messages" && request.method === "GET") {
        handlePoll(response, room, peerId, url, origin);
        return;
      }

      if (action === "messages" && request.method === "POST") {
        const body = await readJson(request);
        if (typeof body.toPeerId !== "string" || !room.members.has(body.toPeerId) || body.toPeerId === peerId) {
          throw new HttpError(400, "INVALID_RECIPIENT", "toPeerId must identify the other room member.");
        }
        if (typeof body.type !== "string" || !MESSAGE_TYPES.has(body.type)) {
          throw new HttpError(400, "INVALID_MESSAGE_TYPE", "Only offer, answer, ice-candidate and hangup are allowed.");
        }
        if (!body.payload || typeof body.payload !== "object" || Array.isArray(body.payload)) {
          throw new HttpError(400, "INVALID_PAYLOAD", "payload must be a JSON object.");
        }
        if (Buffer.byteLength(JSON.stringify(body.payload), "utf8") > MAX_PAYLOAD_BYTES) {
          throw new HttpError(413, "PAYLOAD_TOO_LARGE", "Signaling payload exceeds the 48 KiB limit.");
        }
        const message = appendMessage(room, {
          fromPeerId: peerId,
          toPeerId: body.toPeerId,
          type: body.type,
          payload: body.payload
        });
        json(response, 202, { accepted: true, sequence: message.sequence }, origin);
        return;
      }

      if (action === "turn-credentials" && request.method === "POST") {
        json(response, 200, getTurnCredentials(peerId), origin);
        return;
      }

      if (action === "leave" && request.method === "POST") {
        room.members.delete(peerId);
        const remainingPeerId = [...room.members.keys()][0];
        if (remainingPeerId) {
          appendMessage(room, {
            fromPeerId: peerId,
            toPeerId: remainingPeerId,
            type: "peer-left",
            payload: {}
          });
        } else {
          removeRoom(room, 410);
        }
        json(response, 200, { left: true }, origin);
        return;
      }

      if (["messages", "leave", "turn-credentials"].includes(action)) {
        throw new HttpError(405, "METHOD_NOT_ALLOWED", "HTTP method is not supported for this endpoint.");
      }
      throw new HttpError(404, "NOT_FOUND", "Endpoint does not exist.");
    } catch (error) {
      const status = error instanceof HttpError ? error.status : 500;
      const code = error instanceof HttpError ? error.code : "INTERNAL_ERROR";
      const message = error instanceof HttpError
        ? error.message
        : "The signaling server could not process the request.";
      if (status >= 500) console.error("Signaling request failed:", error);
      json(response, status, { error: { code, message } }, origin);
    }
  });

  const cleanupTimer = setInterval(expireRooms, 15_000);
  cleanupTimer.unref?.();

  return {
    server,
    listen() {
      return new Promise((resolve, reject) => {
        const onError = (error) => {
          server.off("listening", onListening);
          reject(error);
        };
        const onListening = () => {
          server.off("error", onError);
          resolve(server.address());
        };
        server.once("error", onError);
        server.once("listening", onListening);
        server.listen(port, host);
      });
    },
    close() {
      clearInterval(cleanupTimer);
      for (const room of rooms.values()) {
        for (const peerId of room.pollers.keys()) {
          closePoller(room, peerId, 503, {
            error: { code: "SERVER_SHUTTING_DOWN", message: "Signaling server is shutting down." }
          });
        }
      }
      return new Promise((resolve, reject) => {
        if (!server.listening) return resolve();
        server.close((error) => error ? reject(error) : resolve());
      });
    }
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const signaling = createSignalingServer();
  signaling.server.on("error", (error) => {
    console.error("Signaling server failed:", error);
    process.exitCode = 1;
  });
  signaling.listen().then((address) => {
    console.log("Signaling service listening on " + address.address + ":" + address.port);
  }).catch((error) => {
    console.error("Could not start signaling service:", error);
    process.exitCode = 1;
  });

  for (const signal of ["SIGINT", "SIGTERM"]) {
    process.on(signal, () => {
      signaling.close().then(() => process.exit(0)).catch(() => process.exit(1));
    });
  }
}
