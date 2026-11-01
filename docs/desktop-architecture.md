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

The app requires Java 21 to be installed on the host. The package contains the Java application and its runtime dependencies, not a bundled JVM.

## JSON Lines request handling

Every command is serialized as one newline-terminated JSON object with a generated UUID `id`. `JsonLineClient` stores a pending promise per ID and resolves it only when a matching response arrives. Requests have finite timeouts; pending promises are rejected if the process exits. Java events are emitted separately and forwarded to the Renderer on the single `p2p:event` channel.

The client rejects oversized (>1 MiB) stdout lines and treats malformed JSON as a transport diagnostic. The existing Java bridge remains responsible for command validation, P2P protocol state and file transfer behavior.

## IPC allowlist

Preload exposes typed methods only:

- Backend: get status, restart backend.
- Peer: start, stop, get local status, list, connect, disconnect.
- Share: choose a directory using a native dialog, get configuration, list local files.
- Files: list one peer's files and search connected peers.
- Transfers: choose a destination folder and queue download, cancel, list transfers.
- Events: subscribe to Java backend events and receive an unsubscribe callback.

There is no generic `send(command, payload)` or arbitrary channel argument exposed to the Renderer. Main validates ports, endpoint strings, UUIDs and file names again before dispatch. Native dialogs own the share and download directory selections.

## BrowserWindow security

- `contextIsolation: true`
- `sandbox: true`
- `nodeIntegration: false`
- `webSecurity: true`
- a restrictive Content Security Policy; inline styles are allowed for the Renderer progress indicator, while inline scripts remain disallowed
- external navigation and new windows are denied

The preload imports Electron only; Node process and filesystem APIs remain in Main.

## Shutdown and failure behavior

On app close, Main prevents immediate quit, sends `app.shutdown` to Java, closes stdin and waits for a bounded grace period. If the child does not exit, Main escalates to SIGTERM and then SIGKILL. An unexpected process exit rejects outstanding requests and publishes a backend failure event; the UI must not retain a stale RUNNING peer state.

The P2P TCP layer currently targets trusted loopback/LAN environments. Peer authentication and transport encryption are not implemented by this desktop integration.
