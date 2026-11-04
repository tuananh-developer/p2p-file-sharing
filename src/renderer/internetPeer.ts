import type { InternetReceiveHandle, InternetSharedFileHandle } from "../../electron/shared/contracts";

const PREFIX = "p2pfs1.";
const CHUNK_BYTES = 48 * 1024;
const WINDOW_BYTES = CHUNK_BYTES * 4;
const MAX_FILE_BYTES = 50 * 1024 * 1024 * 1024;

export interface InternetSessionSnapshot {
  status: "IDLE" | "SIGNALING" | "CONNECTING" | "VERIFYING" | "CONNECTED" | "CLOSED" | "FAILED";
  invite: string | null;
  authenticationCode: string | null;
  localConfirmed: boolean;
  remoteConfirmed: boolean;
  secure: boolean;
  detail: string;
}

export interface InternetIncomingFileOffer {
  transferId: string;
  fileName: string;
  size: number;
  sha256: string;
}

export interface InternetTransferProgress {
  transferId: string;
  fileName: string;
  direction: "SENDING" | "RECEIVING";
  transferredBytes: number;
  totalBytes: number;
  status: "OFFERED" | "TRANSFERRING" | "COMPLETED" | "FAILED";
  detail: string;
}

interface Invite {
  version: 1;
  signalingUrl: string;
  roomId: string;
  roomSecret: string;
  hostPeerId: string;
  expiresAt: number;
}

interface SignalMessage {
  sequence: number;
  type: string;
  fromPeerId: string;
  toPeerId: string;
  payload: Record<string, unknown>;
}

interface FileAdapter {
  openSharedFile(fileName: string): Promise<InternetSharedFileHandle>;
  readSharedFileChunk(input: { handleId: string; offset: number; length: number }): Promise<Uint8Array>;
  closeSharedFile(handleId: string): Promise<void>;
  beginReceive(input: { fileName: string; size: number }): Promise<InternetReceiveHandle | null>;
  writeReceivedChunk(input: { handleId: string; offset: number; chunk: Uint8Array }): Promise<number>;
  completeReceive(input: { handleId: string; sha256: string }): Promise<{ fileName: string; size: number; sha256: string }>;
  abortReceive(handleId: string): Promise<void>;
}

interface Callbacks {
  onState(snapshot: InternetSessionSnapshot): void;
  onIncomingFile(offer: InternetIncomingFileOffer | null): void;
  onProgress(progress: InternetTransferProgress | null): void;
  onError(message: string): void;
}

interface Outgoing {
  id: string;
  handleId: string;
  name: string;
  size: number;
  hash: string;
  offset: number;
  acked: number;
  pumping: boolean;
  endSent: boolean;
}

interface Incoming {
  id: string;
  handleId: string;
  name: string;
  size: number;
  hash: string;
  offset: number;
}

function normalizeUrl(value: string): string {
  let url: URL;
  try {
    url = new URL(value.trim());
  } catch {
    throw new Error("Signaling URL must be a valid HTTPS URL.");
  }
  const localHttp = url.protocol === "http:" &&
    (url.hostname === "localhost" || url.hostname === "127.0.0.1");
  if (url.protocol !== "https:" && !localHttp) {
    throw new Error("Signaling URL must use HTTPS (HTTP is allowed only for localhost testing).");
  }
  if (url.username || url.password || url.search || url.hash) {
    throw new Error("Signaling URL must not contain credentials, query parameters or a fragment.");
  }
  return url.origin + url.pathname.replace(/\/+$/, "");
}

function encodeInvite(invite: Invite): string {
  const bytes = new TextEncoder().encode(JSON.stringify(invite));
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return PREFIX + btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function decodeInvite(value: string): Invite {
  const text = value.trim();
  if (!text.startsWith(PREFIX) || text.length > 4096) {
    throw new Error("Invite code is invalid or too long.");
  }
  const encoded = text.slice(PREFIX.length).replace(/-/g, "+").replace(/_/g, "/");
  let valueObject: unknown;
  try {
    const binary = atob(encoded + "=".repeat((4 - encoded.length % 4) % 4));
    const bytes = Uint8Array.from(binary, (item) => item.charCodeAt(0));
    valueObject = JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    throw new Error("Invite code could not be decoded.");
  }
  if (!valueObject || typeof valueObject !== "object") throw new Error("Invite payload is invalid.");
  const invite = valueObject as Partial<Invite>;
  if (invite.version !== 1 ||
      typeof invite.signalingUrl !== "string" ||
      typeof invite.roomId !== "string" || !/^[A-Za-z0-9_-]{20,40}$/.test(invite.roomId) ||
      typeof invite.roomSecret !== "string" || !/^[A-Za-z0-9_-]{40,64}$/.test(invite.roomSecret) ||
      typeof invite.hostPeerId !== "string" || !/^[0-9a-f-]{36}$/i.test(invite.hostPeerId) ||
      typeof invite.expiresAt !== "number" || !Number.isSafeInteger(invite.expiresAt) ||
      invite.expiresAt <= Date.now()) {
    throw new Error("Invite code is malformed or expired.");
  }
  return {
    version: 1,
    signalingUrl: normalizeUrl(invite.signalingUrl),
    roomId: invite.roomId,
    roomSecret: invite.roomSecret,
    hostPeerId: invite.hostPeerId.toLowerCase(),
    expiresAt: invite.expiresAt
  };
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function parseFingerprint(sdp: string): string {
  const match = /^a=fingerprint:sha-256\s+([0-9a-f:]+)\s*$/im.exec(sdp);
  if (!match?.[1]) throw new Error("Could not read the DTLS SHA-256 fingerprint.");
  const value = match[1].replace(/:/g, "").toUpperCase();
  if (!/^[0-9A-F]{64}$/.test(value)) throw new Error("The DTLS fingerprint is invalid.");
  return value;
}

async function safetyCode(first: string, second: string): Promise<string> {
  const data = new TextEncoder().encode([first, second].sort().join(":"));
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", data));
  const digits = (new DataView(digest.buffer).getUint32(0, false) % 100_000_000)
    .toString().padStart(8, "0");
  return digits.slice(0, 4) + "-" + digits.slice(4);
}

export class InternetPeerSession {
  private readonly peerId: string;
  private readonly files: FileAdapter;
  private readonly callbacks: Callbacks;
  private serverUrl = "";
  private roomId = "";
  private roomSecret = "";
  private remotePeerId = "";
  private inviteCode: string | null = null;
  private pc: RTCPeerConnection | null = null;
  private channel: RTCDataChannel | null = null;
  private pollAbort: AbortController | null = null;
  private closed = false;
  private offerStarted = false;
  private cursor = 0;
  private messageQueue: Promise<void> = Promise.resolve();
  private authCode: string | null = null;
  private localConfirmed = false;
  private remoteConfirmed = false;
  private incomingOffer: InternetIncomingFileOffer | null = null;
  private incoming: Incoming | null = null;
  private outgoing: Outgoing | null = null;
  private pendingChunk: { id: string; offset: number; length: number } | null = null;

  constructor(peerId: string, files: FileAdapter, callbacks: Callbacks) {
    if (!/^[0-9a-f-]{36}$/i.test(peerId)) throw new Error("Local peer ID is invalid.");
    this.peerId = peerId.toLowerCase();
    this.files = files;
    this.callbacks = callbacks;
  }

  snapshot(): InternetSessionSnapshot {
    const secure = this.localConfirmed && this.remoteConfirmed;
    const connected = this.channel?.readyState === "open";
    return {
      status: this.closed ? "CLOSED" : secure ? "CONNECTED" : this.authCode ? "VERIFYING" :
        connected ? "CONNECTING" : this.roomId ? "SIGNALING" : "IDLE",
      invite: this.inviteCode,
      authenticationCode: this.authCode,
      localConfirmed: this.localConfirmed,
      remoteConfirmed: this.remoteConfirmed,
      secure,
      detail: this.closed ? "Internet peer session closed." :
        secure ? "DTLS-encrypted DataChannel verified by matching safety codes." :
        this.authCode ? "Compare the safety code with the other person before confirming." :
        connected ? "Data channel open; waiting for peer identity verification." :
        this.roomId ? "Waiting for peer and ICE connectivity." : "No Internet peer session."
    };
  }

  private publish(): void {
    this.callbacks.onState(this.snapshot());
  }

  private fail(error: unknown): void {
    const message = errorText(error);
    this.callbacks.onError(message);
    this.callbacks.onState({ ...this.snapshot(), status: "FAILED", detail: message });
  }

  async createInvite(signalingUrl: string): Promise<string> {
    this.assertFresh();
    this.serverUrl = normalizeUrl(signalingUrl);
    this.publish();
    try {
      const room = await this.request<{ roomId: string; roomSecret: string; peerId: string; expiresAt: number }>(
        "/v1/rooms",
        {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ peerId: this.peerId })
        },
        false
      );
      this.roomId = room.roomId;
      this.roomSecret = room.roomSecret;
      this.inviteCode = encodeInvite({
        version: 1,
        signalingUrl: this.serverUrl,
        roomId: room.roomId,
        roomSecret: room.roomSecret,
        hostPeerId: this.peerId,
        expiresAt: room.expiresAt
      });
      await this.createConnection(true);
      this.startPolling();
      this.publish();
      return this.inviteCode;
    } catch (error) {
      this.fail(error);
      throw error;
    }
  }

  async joinInvite(code: string): Promise<void> {
    this.assertFresh();
    try {
      const invite = decodeInvite(code);
      if (invite.hostPeerId === this.peerId) throw new Error("You cannot join an invite created by this device.");
      this.serverUrl = invite.signalingUrl;
      this.roomId = invite.roomId;
      this.roomSecret = invite.roomSecret;
      this.remotePeerId = invite.hostPeerId;
      await this.request("/v1/rooms/" + this.roomId + "/join", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ peerId: this.peerId })
      });
      await this.createConnection(false);
      this.startPolling();
      this.publish();
    } catch (error) {
      this.fail(error);
      throw error;
    }
  }

  private assertFresh(): void {
    if (this.closed || this.roomId) throw new Error("An Internet peer session is already active.");
  }

  private async request<T>(path: string, init: RequestInit = {}, authorized = true): Promise<T> {
    const headers = new Headers(init.headers);
    if (authorized) {
      headers.set("authorization", "Bearer " + this.roomSecret);
      headers.set("x-peer-id", this.peerId);
    }
    const response = await fetch(this.serverUrl + path, {
      ...init,
      headers,
      cache: "no-store",
      credentials: "omit",
      redirect: "error"
    });
    const body = await response.json().catch(() => null) as
      | { error?: { message?: string } }
      | T | null;
    if (!response.ok) {
      const errorBody = body as { error?: { message?: string } } | null;
      throw new Error(errorBody?.error?.message ?? "Signaling server returned HTTP " + response.status + ".");
    }
    if (!body || typeof body !== "object") throw new Error("Signaling server returned an invalid response.");
    return body as T;
  }

  private async createConnection(initiator: boolean): Promise<void> {
    let iceServers: RTCIceServer[] = [{ urls: "stun:stun.l.google.com:19302" }];
    try {
      const config = await this.request<{ iceServers?: RTCIceServer[] }>(
        "/v1/rooms/" + this.roomId + "/turn-credentials",
        { method: "POST" }
      );
      if (Array.isArray(config.iceServers) && config.iceServers.length) iceServers = config.iceServers;
    } catch (error) {
      this.callbacks.onError("TURN relay is not configured or unavailable; trying direct ICE only. " + errorText(error));
    }

    const connection = new RTCPeerConnection({ iceServers, iceCandidatePoolSize: 2 });
    this.pc = connection;
    connection.onconnectionstatechange = () => {
      if (connection.connectionState === "failed") {
        this.fail(new Error("ICE connection failed. This network may require a TURN relay."));
      } else {
        this.publish();
      }
    };
    connection.oniceconnectionstatechange = () => {
      if (connection.iceConnectionState === "failed") {
        this.fail(new Error("ICE connectivity checks failed. Check STUN/TURN reachability."));
      }
    };
    connection.ondatachannel = (event) => this.attachChannel(event.channel);
    if (initiator) {
      this.attachChannel(connection.createDataChannel("p2p-file-transfer", { ordered: true }));
    }
  }

  private attachChannel(channel: RTCDataChannel): void {
    if (this.channel && this.channel !== channel) {
      channel.close();
      return;
    }
    this.channel = channel;
    channel.binaryType = "arraybuffer";
    channel.onopen = () => {
      this.publish();
      void this.updateSafetyCode().catch((error) => this.fail(error));
    };
    channel.onclose = () => this.publish();
    channel.onerror = () => this.fail(new Error("WebRTC DataChannel reported an error."));
    channel.onmessage = (event) => {
      this.messageQueue = this.messageQueue
        .then(() => this.handleData(event.data))
        .catch((error: unknown) => this.fail(error));
    };
  }

  private waitIce(): Promise<void> {
    const connection = this.pc;
    if (!connection) return Promise.reject(new Error("WebRTC connection is unavailable."));
    if (connection.iceGatheringState === "complete") return Promise.resolve();
    return new Promise((resolve, reject) => {
      const timeout = window.setTimeout(() => {
        cleanup();
        reject(new Error("ICE candidate gathering timed out after 25 seconds."));
      }, 25_000);
      const changed = () => {
        if (connection.iceGatheringState === "complete") {
          cleanup();
          resolve();
        }
      };
      const cleanup = () => {
        window.clearTimeout(timeout);
        connection.removeEventListener("icegatheringstatechange", changed);
      };
      connection.addEventListener("icegatheringstatechange", changed);
      changed();
    });
  }

  private async makeOffer(): Promise<void> {
    if (this.offerStarted || !this.pc) return;
    this.offerStarted = true;
    try {
      await this.pc.setLocalDescription(await this.pc.createOffer());
      await this.waitIce();
      const sdp = this.pc.localDescription?.sdp;
      if (!sdp) throw new Error("WebRTC did not produce an SDP offer.");
      await this.sendSignal("offer", { sdp });
      this.publish();
    } catch (error) {
      this.offerStarted = false;
      this.fail(error);
      throw error;
    }
  }

  private async sendSignal(type: string, payload: Record<string, unknown>): Promise<void> {
    if (!this.remotePeerId) throw new Error("The other peer has not joined the room.");
    await this.request("/v1/rooms/" + this.roomId + "/messages", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ toPeerId: this.remotePeerId, type, payload })
    });
  }

  private startPolling(): void {
    if (this.pollAbort) return;
    const controller = new AbortController();
    this.pollAbort = controller;
    void (async () => {
      while (!this.closed && !controller.signal.aborted) {
        try {
          const response = await fetch(
            this.serverUrl + "/v1/rooms/" + this.roomId + "/messages?after=" + this.cursor + "&wait=20000",
            {
              headers: {
                authorization: "Bearer " + this.roomSecret,
                "x-peer-id": this.peerId
              },
              cache: "no-store",
              credentials: "omit",
              redirect: "error",
              signal: controller.signal
            }
          );
          const body = await response.json().catch(() => null) as
            | { messages?: SignalMessage[]; error?: { message?: string } }
            | null;
          if (!response.ok) throw new Error(body?.error?.message ?? "Signaling poll failed (" + response.status + ").");
          if (!body || !Array.isArray(body.messages)) throw new Error("Signaling poll response was invalid.");
          for (const message of body.messages) {
            if (message.sequence > this.cursor) this.cursor = message.sequence;
            await this.handleSignal(message);
          }
        } catch (error) {
          if (controller.signal.aborted || this.closed) break;
          this.callbacks.onError("Signaling connection interrupted: " + errorText(error));
          await new Promise((resolve) => window.setTimeout(resolve, 1_000));
        }
      }
    })();
  }

  private async handleSignal(message: SignalMessage): Promise<void> {
    if (message.toPeerId !== this.peerId || message.fromPeerId === this.peerId) return;
    if (!this.remotePeerId) this.remotePeerId = message.fromPeerId;
    if (message.fromPeerId !== this.remotePeerId) return;

    if (message.type === "peer-joined") {
      await this.makeOffer();
    } else if (message.type === "offer") {
      if (!this.pc || typeof message.payload.sdp !== "string") throw new Error("Received an invalid WebRTC offer.");
      await this.pc.setRemoteDescription({ type: "offer", sdp: message.payload.sdp });
      await this.pc.setLocalDescription(await this.pc.createAnswer());
      await this.waitIce();
      const sdp = this.pc.localDescription?.sdp;
      if (!sdp) throw new Error("WebRTC did not produce an SDP answer.");
      await this.sendSignal("answer", { sdp });
      await this.updateSafetyCode();
    } else if (message.type === "answer") {
      if (!this.pc || typeof message.payload.sdp !== "string") throw new Error("Received an invalid WebRTC answer.");
      await this.pc.setRemoteDescription({ type: "answer", sdp: message.payload.sdp });
      await this.updateSafetyCode();
    } else if (message.type === "hangup" || message.type === "peer-left") {
      await this.close(false);
      this.callbacks.onError("The other peer left the Internet session.");
    }
    this.publish();
  }

  private async updateSafetyCode(): Promise<void> {
    const local = this.pc?.localDescription?.sdp;
    const remote = this.pc?.remoteDescription?.sdp;
    if (!local || !remote || this.authCode) return;
    this.authCode = await safetyCode(parseFingerprint(local), parseFingerprint(remote));
    this.publish();
  }

  confirmSafetyCode(): void {
    if (!this.authCode || this.channel?.readyState !== "open") {
      throw new Error("The WebRTC connection is not ready for safety-code verification.");
    }
    if (this.localConfirmed) return;
    this.localConfirmed = true;
    this.sendControl({ type: "security-confirm" });
    this.publish();
  }

  private secure(): boolean {
    return this.localConfirmed && this.remoteConfirmed;
  }

  private sendControl(value: Record<string, unknown>): void {
    if (!this.channel || this.channel.readyState !== "open") throw new Error("WebRTC DataChannel is not open.");
    this.channel.send(JSON.stringify(value));
  }

  async sendFile(fileName: string): Promise<void> {
    if (!this.secure()) throw new Error("Both devices must confirm the matching safety code first.");
    if (this.outgoing || this.incoming || this.incomingOffer) throw new Error("Finish the current Internet transfer first.");
    const source = await this.files.openSharedFile(fileName);
    const transferId = crypto.randomUUID();
    this.outgoing = {
      id: transferId,
      handleId: source.handleId,
      name: source.fileName,
      size: source.size,
      hash: source.sha256,
      offset: 0,
      acked: 0,
      pumping: false,
      endSent: false
    };
    this.callbacks.onProgress({
      transferId,
      fileName: source.fileName,
      direction: "SENDING",
      transferredBytes: 0,
      totalBytes: source.size,
      status: "OFFERED",
      detail: "Waiting for the recipient to accept."
    });
    try {
      this.sendControl({
        type: "file-offer",
        transferId,
        fileName: source.fileName,
        size: source.size,
        sha256: source.sha256
      });
    } catch (error) {
      this.outgoing = null;
      await this.files.closeSharedFile(source.handleId).catch(() => undefined);
      throw error;
    }
  }

  async acceptIncomingFile(): Promise<void> {
    const offer = this.incomingOffer;
    if (!offer || !this.secure()) throw new Error("There is no verified incoming file offer.");
    const target = await this.files.beginReceive({ fileName: offer.fileName, size: offer.size });
    if (!target) return;
    this.incoming = {
      id: offer.transferId,
      handleId: target.handleId,
      name: target.fileName,
      size: target.size,
      hash: offer.sha256,
      offset: 0
    };
    this.incomingOffer = null;
    this.callbacks.onIncomingFile(null);
    this.callbacks.onProgress({
      transferId: offer.transferId,
      fileName: target.fileName,
      direction: "RECEIVING",
      transferredBytes: 0,
      totalBytes: offer.size,
      status: "TRANSFERRING",
      detail: "Receiving file from the remote peer."
    });
    this.sendControl({ type: "file-accept", transferId: offer.transferId });
  }

  rejectIncomingFile(): void {
    const offer = this.incomingOffer;
    if (!offer) return;
    this.incomingOffer = null;
    this.callbacks.onIncomingFile(null);
    this.callbacks.onProgress(null);
    this.sendControl({ type: "file-reject", transferId: offer.transferId });
  }

  async cancelTransfer(): Promise<void> {
    if (this.outgoing) {
      const transfer = this.outgoing;
      this.outgoing = null;
      await this.files.closeSharedFile(transfer.handleId).catch(() => undefined);
      this.sendControl({ type: "file-cancel", transferId: transfer.id });
    }
    if (this.incoming) {
      const transfer = this.incoming;
      this.incoming = null;
      await this.files.abortReceive(transfer.handleId).catch(() => undefined);
      this.sendControl({ type: "file-cancel", transferId: transfer.id });
    }
    this.callbacks.onProgress(null);
  }

  private async handleData(data: unknown): Promise<void> {
    if (typeof data === "string") {
      let message: Record<string, unknown>;
      try {
        message = JSON.parse(data) as Record<string, unknown>;
      } catch {
        throw new Error("Remote peer sent invalid DataChannel control JSON.");
      }
      await this.handleControl(message);
      return;
    }

    if (!(data instanceof ArrayBuffer)) throw new Error("Unsupported DataChannel message type.");
    const transfer = this.incoming;
    const header = this.pendingChunk;
    if (!transfer || !header || header.id !== transfer.id) throw new Error("File bytes arrived without a chunk header.");
    const chunk = new Uint8Array(data);
    if (chunk.byteLength !== header.length || header.offset !== transfer.offset) {
      throw new Error("File chunk is out of order or truncated.");
    }
    transfer.offset = await this.files.writeReceivedChunk({
      handleId: transfer.handleId,
      offset: header.offset,
      chunk
    });
    this.pendingChunk = null;
    this.sendControl({ type: "file-ack", transferId: transfer.id, nextOffset: transfer.offset });
    this.callbacks.onProgress({
      transferId: transfer.id,
      fileName: transfer.name,
      direction: "RECEIVING",
      transferredBytes: transfer.offset,
      totalBytes: transfer.size,
      status: "TRANSFERRING",
      detail: "Receiving file from the remote peer."
    });
  }

  private async handleControl(message: Record<string, unknown>): Promise<void> {
    if (message.type === "security-confirm") {
      this.remoteConfirmed = true;
      this.publish();
      return;
    }
    if (!this.secure()) throw new Error("Remote peer attempted file transfer before safety-code verification.");

    switch (message.type) {
      case "file-offer": {
        const transferId = typeof message.transferId === "string" ? message.transferId : "";
        const fileName = typeof message.fileName === "string" ? message.fileName : "";
        const size = message.size;
        const sha256 = typeof message.sha256 === "string" ? message.sha256.toLowerCase() : "";
        if (this.incoming || this.incomingOffer || this.outgoing ||
            !/^[0-9a-f-]{36}$/i.test(transferId) ||
            !fileName || fileName.length > 255 || fileName === "." || fileName === ".." ||
            fileName.includes("/") || fileName.includes("\\") ||
            typeof size !== "number" || !Number.isSafeInteger(size) || size < 0 || size > MAX_FILE_BYTES ||
            !/^[0-9a-f]{64}$/.test(sha256)) {
          this.sendControl({ type: "file-reject", transferId });
          return;
        }
        this.incomingOffer = { transferId, fileName, size, sha256 };
        this.callbacks.onIncomingFile(this.incomingOffer);
        this.callbacks.onProgress({
          transferId,
          fileName,
          direction: "RECEIVING",
          transferredBytes: 0,
          totalBytes: size,
          status: "OFFERED",
          detail: "Waiting for you to accept the incoming file."
        });
        break;
      }
      case "file-accept":
        if (this.outgoing?.id === message.transferId) await this.pump();
        break;
      case "file-reject":
        {
          const transfer = this.outgoing;
          if (!transfer || transfer.id !== message.transferId) break;
          this.outgoing = null;
          await this.files.closeSharedFile(transfer.handleId).catch(() => undefined);
          this.callbacks.onProgress({
            transferId: transfer.id,
            fileName: transfer.name,
            direction: "SENDING",
            transferredBytes: 0,
            totalBytes: transfer.size,
            status: "FAILED",
            detail: "The recipient declined the file."
          });
        }
        break;
      case "file-chunk": {
        const transfer = this.incoming;
        const offset = message.offset;
        const length = message.length;
        if (!transfer || transfer.id !== message.transferId || this.pendingChunk ||
            typeof offset !== "number" || !Number.isSafeInteger(offset) ||
            typeof length !== "number" || !Number.isInteger(length) ||
            length < 1 || length > CHUNK_BYTES || offset !== transfer.offset ||
            offset + length > transfer.size) {
          throw new Error("Remote peer sent an invalid file chunk header.");
        }
        this.pendingChunk = { id: transfer.id, offset, length };
        break;
      }
      case "file-ack": {
        const transfer = this.outgoing;
        const nextOffset = message.nextOffset;
        if (!transfer || transfer.id !== message.transferId ||
            typeof nextOffset !== "number" || !Number.isSafeInteger(nextOffset) ||
            nextOffset < transfer.acked || nextOffset > transfer.offset) {
          throw new Error("Remote peer sent an invalid file acknowledgement.");
        }
        transfer.acked = nextOffset;
        this.callbacks.onProgress({
          transferId: transfer.id,
          fileName: transfer.name,
          direction: "SENDING",
          transferredBytes: nextOffset,
          totalBytes: transfer.size,
          status: "TRANSFERRING",
          detail: "Sending file to the remote peer."
        });
        await this.pump();
        break;
      }
      case "file-end": {
        const transfer = this.incoming;
        if (!transfer || transfer.id !== message.transferId || this.pendingChunk || transfer.offset !== transfer.size) {
          throw new Error("Remote peer ended transfer before all bytes arrived.");
        }
        const completed = await this.files.completeReceive({
          handleId: transfer.handleId,
          sha256: transfer.hash
        });
        this.incoming = null;
        this.sendControl({ type: "file-complete", transferId: transfer.id });
        this.callbacks.onProgress({
          transferId: transfer.id,
          fileName: completed.fileName,
          direction: "RECEIVING",
          transferredBytes: completed.size,
          totalBytes: completed.size,
          status: "COMPLETED",
          detail: "File received and SHA-256 verified."
        });
        break;
      }
      case "file-complete": {
        const transfer = this.outgoing;
        if (!transfer || transfer.id !== message.transferId || transfer.acked !== transfer.size) {
          throw new Error("Remote peer reported completion before all bytes were acknowledged.");
        }
        await this.files.closeSharedFile(transfer.handleId);
        this.outgoing = null;
        this.callbacks.onProgress({
          transferId: transfer.id,
          fileName: transfer.name,
          direction: "SENDING",
          transferredBytes: transfer.size,
          totalBytes: transfer.size,
          status: "COMPLETED",
          detail: "Recipient verified the file SHA-256."
        });
        break;
      }
      case "file-cancel": {
        const outgoing = this.outgoing;
        if (outgoing && outgoing.id === message.transferId) {
          await this.files.closeSharedFile(outgoing.handleId).catch(() => undefined);
          this.outgoing = null;
        }
        const incoming = this.incoming;
        if (incoming && incoming.id === message.transferId) {
          await this.files.abortReceive(incoming.handleId).catch(() => undefined);
          this.incoming = null;
        }
        this.callbacks.onProgress(null);
        break;
      }
      default:
        throw new Error("Remote peer sent an unsupported file-transfer message.");
    }
  }

  private async pump(): Promise<void> {
    const transfer = this.outgoing;
    const channel = this.channel;
    if (!transfer || !channel || channel.readyState !== "open" || transfer.pumping) return;
    transfer.pumping = true;
    try {
      while (transfer.offset < transfer.size && transfer.offset - transfer.acked < WINDOW_BYTES) {
        const length = Math.min(CHUNK_BYTES, transfer.size - transfer.offset);
        const chunk = await this.files.readSharedFileChunk({
          handleId: transfer.handleId,
          offset: transfer.offset,
          length
        });
        if (chunk.byteLength !== length) throw new Error("Could not read a complete shared-file chunk.");
        channel.send(JSON.stringify({
          type: "file-chunk",
          transferId: transfer.id,
          offset: transfer.offset,
          length
        }));
        const payload = new ArrayBuffer(chunk.byteLength);
        new Uint8Array(payload).set(chunk);
        channel.send(payload);
        transfer.offset += length;
      }
      if (transfer.offset === transfer.size && transfer.acked === transfer.size && !transfer.endSent) {
        transfer.endSent = true;
        this.sendControl({ type: "file-end", transferId: transfer.id });
      }
    } finally {
      transfer.pumping = false;
    }
  }

  async close(notifyRemote = true): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    this.pollAbort?.abort();
    this.pollAbort = null;

    if (notifyRemote && this.roomId && this.remotePeerId) {
      await this.request("/v1/rooms/" + this.roomId + "/messages", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ toPeerId: this.remotePeerId, type: "hangup", payload: {} })
      }).catch(() => undefined);
    }
    if (this.roomId) {
      await this.request("/v1/rooms/" + this.roomId + "/leave", { method: "POST" }).catch(() => undefined);
    }
    if (this.outgoing) {
      await this.files.closeSharedFile(this.outgoing.handleId).catch(() => undefined);
      this.outgoing = null;
    }
    if (this.incoming) {
      await this.files.abortReceive(this.incoming.handleId).catch(() => undefined);
      this.incoming = null;
    }
    this.channel?.close();
    this.pc?.close();
    this.callbacks.onIncomingFile(null);
    this.callbacks.onProgress(null);
    this.publish();
  }
}
