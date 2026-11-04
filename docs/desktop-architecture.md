# Desktop integration architecture

## Runtime ownership

The Electron Main process owns the Java child process and all privileged desktop APIs. React runs in the sandboxed Renderer and never reads files, starts processes, or opens P2P sockets directly.

```text
React Renderer
    │ allowlisted window.p2p API
    ▼
Preload (contextBridge)
    │ fixed ipcRenderer.invoke channels
    ▼
Electron Main
    │ JsonLineClient: request ID ↔ response ID
    │ spawn(java, -cp build/install/file_sharing/lib/*, main class)
    ▼
Java P2P Core
    │ direct TCP sockets between peers
    ▼
Remote Java P2P Core
```

File bytes flow only between Java peers over TCP. Electron/React carries control commands, metadata, progress and lifecycle events; it is not a file-content proxy.

## Startup and Java distribution

1. Electron Main resolves Java from `P2P_JAVA_EXECUTABLE`, then `JAVA_HOME/bin/java`, then `PATH`.
2. In development, the classpath is `build/install/file_sharing/lib/*`; in a packaged Linux app, Gradle runtime libraries are copied to `resources/backend/lib`.
3. The Java main class is `com.tanh.file_sharing.FileSharingApplication`.
4. Electron waits up to 15 seconds for the JSON Lines event `backendStatusChanged: READY`. A process spawn error, invalid stdout frame, startup timeout or unexpected exit updates backend status to FAILED.
5. Java stdout is a protocol stream. Java diagnostics are read from stderr and are never parsed as JSON Lines.

The app requires Java 27 to be installed on the host. The package contains the Java application and its runtime dependencies, not a bundled JVM.

## JSON Lines request handling

Every command is serialized as one newline-terminated JSON object with a generated UUID `id`. `JsonLineClient` stores a pending promise per ID and resolves it only when a matching response arrives. Requests have finite timeouts; pending promises are rejected if the process exits. Java events are emitted separately and forwarded to the Renderer on the single `p2p:event` channel.

The client rejects oversized (>1 MiB) stdout lines and treats malformed JSON as a transport diagnostic. The existing Java bridge remains responsible for command validation, P2P protocol state and file transfer behavior.

## IPC allowlist

Preload exposes typed methods only:

- Backend: get status, restart backend.
- Peer: start, stop, get local status, list, connect, disconnect.
- Share: choose a directory using a native dialog, get configuration, list local files.
- Files: list one peer's files and search connected peers.
- Security: read the local TLS identity fingerprint, add a verified remote SHA-256 fingerprint to the trust list, and revoke a fingerprint.
- Transfers: choose a destination folder and queue single- or multi-source ranged downloads, cancel, list transfers.
- Events: subscribe to Java backend events and receive an unsubscribe callback.

There is no generic `send(command, payload)` or arbitrary channel argument exposed to the Renderer. Main validates ports, endpoint strings, UUIDs and file names again before dispatch. Native dialogs own the share and download directory selections.

## BrowserWindow security

- `contextIsolation: true`
- `sandbox: true`
- `nodeIntegration: false`
- `webSecurity: true`
- a restrictive Content Security Policy; inline styles are allowed for the Renderer progress indicator, while inline scripts remain disallowed
- all renderer-initiated navigation and new windows are denied

The preload imports Electron only; Node process and filesystem APIs remain in Main.

## Shutdown and failure behavior

On app close, Main prevents immediate quit, sends `app.shutdown` to Java, closes stdin and waits for a bounded grace period. If the child does not exit, Main escalates to SIGTERM and then SIGKILL. An unexpected process exit rejects outstanding requests and publishes a backend failure event; the UI must not retain a stale RUNNING peer state.

The P2P listener defaults to Auto IPv4 binding (`0.0.0.0`) and an OS-assigned listening port (`0`). The Java peer snapshot lists active non-loopback IPv4 addresses and the actual bound port; a user can instead bind to a specific local IPv4 address and fixed port.

P2P traffic uses TLS 1.3 mutual certificate authentication. Each installation stores an RSA-3072 private key and self-signed X.509 certificate under `~/.p2p-file-sharing`; the Renderer sees only the SHA-256 fingerprint, never private-key material. Peers are accepted only when each side has explicitly pinned the other side's certificate fingerprint. Fingerprints must be exchanged over a separate trusted channel. The existing JSON handshake and transfer protocol run inside the TLS session; there is no plaintext fallback.

Auto binding listens on all local IPv4 interfaces, so operators should choose a specific interface when a machine has multiple networks (for example Wi-Fi, VPN, containers, or a VM bridge). TLS does not mitigate denial-of-service; keep the listener on a trusted network and do not expose it directly to the public Internet.


## Multi-source transfer behavior

The Renderer groups search hits by filename and advertised size and passes candidate peer IDs to transfer.downloadMulti. Java independently requests source metadata and only assembles bytes from peers with matching complete-file SHA-256. The Electron layer never handles file bytes. The transfer service uses 4 MiB logical ranges, at most eight concurrent range workers, offset-based writes into the existing .part staging file, retry-on-another-matching-peer, and a final full-file SHA-256 check before the no-overwrite rename.

## Internet transport

The desktop renderer uses Chromium's WebRTC implementation for Internet sessions. It creates a short-lived rendezvous room through the HTTPS signaling API, exchanges complete SDP offer/answer descriptions, and waits for ICE gathering to finish before forwarding SDP. Trickle ICE candidates are not currently used. WebRTC DataChannels carry file-control messages and bounded binary chunks; the signaling service never receives file bytes or local filesystem paths.

The Java Core's peer UUID identifies each running backend session and is available even when its TCP listener is stopped. Internet-only sessions do not start the Java TCP listener. The Java TCP/TLS transport remains available for LAN and other directly reachable peers.

Before file operations, each renderer reads the SHA-256 DTLS certificate fingerprints from its local and remote SDP, derives an eight-digit safety code, and requires both users to confirm the matching code over a separate trusted channel. WebRTC DTLS encrypts the DataChannel. The safety-code comparison is the application-layer check against a signaling intermediary substituting SDP. WebRTC safety-code approvals are session-scoped; the persistent RSA certificate pin store continues to protect the Java TLS transport.

File sends use a reliable ordered DataChannel with 48 KiB binary chunks and at most four chunks outstanding. The receiver serializes writes through the Electron Main process to a user-selected temporary file, acknowledges completed chunks, verifies SHA-256, and atomically renames the completed file. The current Internet path does not resume interrupted transfers; Java TCP/LAN transfers retain their existing resumable-download support.
