# P2P TCP Protocol v1

| Property | Value |
|---|---|
| Status | MVP implementation contract |
| Transport | TCP |
| Encoding | UTF-8 |
| Framing | One JSON object per line, terminated by LF (`0x0A`) |
| Maximum frame | 64 KiB |
| Protocol version | `1` |
| Connect + handshake deadline | 5 seconds total |

This contract implements peer lifecycle, shared-file catalog and single-file download (FR-01–FR-19, FR-30). Resume and multi-source transfers remain outside this version.

## 1. Identity and duplicate connection rule

Each Java Core process creates a random UUID `peerId` at startup. The identity remains stable for that process lifetime; it is not persisted across restarts.

A connected peer is identified by `peerId`, not by its source TCP port (which is an ephemeral port). `listeningPort` in the handshake is the remote peer's listening port. The remote host address is derived from the established socket's remote IP address.

To resolve simultaneous cross-connections deterministically:

- If `localPeerId` is lexicographically lower than `remotePeerId`, the preferred connection direction on the local side is `OUTGOING`.
- Otherwise, the preferred direction is `INCOMING`.
- If there is no active connection, accept the first valid direction. This keeps a one-sided user-initiated connect working regardless of UUID ordering.
- If both peers initiate concurrently and a second connection arrives, keep the preferred direction and replace an existing non-preferred connection. If the existing connection is already preferred, reject the duplicate.
- A duplicate connection is never added as a second active peer entry.

This rule causes both ends to converge on the same TCP connection when both peers initiate at the same time, while still allowing a normal one-sided connect. It is a deterministic MVP tie-break, not authentication; peers can still spoof UUIDs.

## 2. Handshake

The initiator sends:

```json
{"type":"handshake","protocolVersion":1,"peerId":"<uuid>","listeningPort":5001}
```

On success, the receiver responds:

```json
{"type":"handshakeAccepted","protocolVersion":1,"peerId":"<uuid>","listeningPort":5002}
```

On rejection, the receiver responds:

```json
{"type":"handshakeRejected","reason":"DUPLICATE_OR_NON_PREFERRED_CONNECTION"}
```

The receiver validates message type, protocol version, UUID and port range. Invalid, unsupported, self and timed-out handshakes are closed without adding a peer to the connected list.

## 3. Connection close

Either side may send:

```json
{"type":"closeConnection","protocolVersion":1}
```

The receiver replies:

```json
{"type":"closeAcknowledged","protocolVersion":1}
```

Then both sides close the socket. An EOF or I/O failure also changes the connection status to `DISCONNECTED`.

## 4. File-list request and response

After the handshake, either connected peer may request the other peer's shared-file metadata. Both peers can issue requests at the same time on one TCP connection.

Request:

```json
{"type":"request","protocolVersion":1,"requestId":"<uuid>","operation":"requestFileList","payload":{}}
```

Success response:

```json
{"type":"response","protocolVersion":1,"requestId":"<uuid>","success":true,"data":{"files":[{"fileName":"report.pdf","size":12345}]}}
```

Error response:

```json
{"type":"response","protocolVersion":1,"requestId":"<uuid>","success":false,"error":{"code":"NOT_SHARING","message":"No shared directory has been configured."}}
```

The requester correlates a response using `requestId`; a response with an unknown ID is ignored. Request IDs must be non-empty strings no longer than 128 characters. The operation is a string no longer than 64 characters. A connection serves up to eight remote requests concurrently; excess work is rejected with `PEER_BUSY`. A request has a five-second response deadline.

The file-list response contains metadata only: `fileName` and non-negative `size` in bytes. It never contains file contents or local filesystem paths. A peer publishes readable regular files directly inside its selected shared directory. Child directories and symbolic links are excluded. Names containing path separators, control characters or `..` are rejected by the receiver and are not published by the sender.

A response is still one JSON frame and is subject to the 64 KiB frame limit. The current version does not paginate a large file catalog; if a response does not fit, the serving peer returns `RESPONSE_TOO_LARGE`. When the user changes the selected directory, requests that have not started use the new directory; a listing already in progress completes against the directory snapshot it opened.


## 5. File download stream

A downloader sends a file request over an established peer connection. The request contains a file name only; absolute paths and relative path components are never accepted.

Request:

```json
{"type":"request","protocolVersion":1,"requestId":"<uuid>","operation":"requestFile","payload":{"fileName":"report.pdf"}}
```

The source opens and validates the direct child of its configured share, rejects symbolic links, computes SHA-256, and returns metadata **before** sending file bytes (decision G-02):

```json
{"type":"response","protocolVersion":1,"requestId":"<uuid>","success":true,"data":{"fileName":"report.pdf","size":12345,"sha256":"<64-hex-sha256>"}}
```

The source then sends zero or more ordered chunks. Each chunk carries at most 24 KiB of raw bytes encoded as Base64, keeping the JSON frame below the 64 KiB maximum:

```json
{"type":"fileChunk","protocolVersion":1,"requestId":"<uuid>","sequence":0,"data":"<base64>"}
```

The final frame declares whether the stream completed and how many bytes were sent:

```json
{"type":"fileEnd","protocolVersion":1,"requestId":"<uuid>","success":true,"totalBytes":12345}
```

When the user cancels, the receiver sends:

```json
{"type":"cancelTransfer","protocolVersion":1,"requestId":"<uuid>"}
```

The source stops reading the file and returns a failed `fileEnd` when possible. A disconnect is treated as a failed transfer. The receiver checks the request ID, protocol version, strictly increasing chunk sequence, Base64 validity, chunk size, total byte count and SHA-256. It writes to `<targetFileName>.part`; only after all checks pass does it move the temporary file to the final target without replacement. Existing destination names are resolved with a suffix such as ` (1)`; an existing file is never overwritten. Temporary files are removed after failure or cancellation.

The bridge returns immediately with a `taskId` and chosen `targetFileName`. Progress and terminal state are reported asynchronously through `transferProgress` and `transferStatusChanged` events. The local peer cancels active downloads before closing peer connections during shutdown (D-03).

## 5. TCP framing

TCP is a byte stream; one `read()` is not assumed to equal one complete message. The implementation reads bytes until LF, decodes the complete UTF-8 line, and parses exactly one JSON object. Frames larger than 64 KiB are rejected. A newline inside a JSON string must be escaped as `\\n` by the JSON serializer.

## 6. Electron ↔ Java bridge

The local process bridge is separate from peer-to-peer TCP. Electron writes one JSON request per stdin line and reads one JSON response/event per stdout line. Every request should contain `id` (or `requestId`) and `command`; responses echo the same `id`. Events are unsolicited lines with `type: event`.

Supported commands:

| Command | Payload | Result |
|---|---|---|
| `peer.start` | `bindAddress`, `port` | `peer` snapshot |
| `peer.stop` | `{}` | `peer` snapshot |
| `peer.connect` | `host`, `port` | connected peer snapshot |
| `peer.disconnect` | `peerId` | disconnected peer snapshot |
| `peer.status` | `{}` | local peer snapshot |
| `peer.list` | `{}` | known peer snapshots |
| `share.setDirectory` | `path` | selected shared directory |
| `share.getDirectory` | `{}` | current session's shared directory |
| `files.listLocal` | `{}` | local top-level file metadata |
| `files.listRemote` | `peerId` | file metadata from one connected peer |
| `files.search` | optional `keyword`, optional `peerIds` | aggregated results and per-peer errors |
| `transfer.download` | `peerId`, `fileName`, `destinationDir` | immediate `taskId` and target name |
| `transfer.cancel` | `taskId` | updated transfer snapshot |
| `transfer.list` | `{}` | known transfer snapshots |
| `app.shutdown` | `{}` | shutdown acknowledgement |

The response envelope is either `{ "type":"response", "id":"...", "success":true, "data":{...} }` or `{ "type":"response", "id":"...", "success":false, "error":{"code":"...","message":"..."} }`.

Events currently emitted are `backendStatusChanged`, `peerStatusChanged`, `connectionStatusChanged`, `sharedDirectoryChanged`, `transferProgress`, `transferStatusChanged`, and `activityLogged`. `files.search` refreshes all connected peers when `peerIds` is omitted; it returns successful results and a separate error item for each peer that could not be refreshed.

## 7. MVP error codes

`INVALID_PORT`, `INVALID_ADDRESS`, `PORT_IN_USE`, `PERMISSION_DENIED`, `PEER_BUSY`, `PEER_NOT_RUNNING`, `CONNECT_TIMEOUT`, `CONNECTION_REFUSED`, `HANDSHAKE_FAILED`, `UNKNOWN_PEER`, `CLEANUP_FAILED`, `INVALID_ARGUMENT`, `UNKNOWN_COMMAND`, `INVALID_JSON`, `NOT_SHARING`, `INVALID_DIRECTORY`, `SHARED_DIRECTORY_UNAVAILABLE`, `PEER_NOT_CONNECTED`, `PEER_REQUEST_TIMEOUT`, `PEER_REQUEST_INTERRUPTED`, `PEER_IO_ERROR`, `INVALID_PEER_RESPONSE`, `UNKNOWN_OPERATION`, `UNSUPPORTED_PROTOCOL_VERSION`, `RESPONSE_TOO_LARGE`, `FILE_UNAVAILABLE`, `FILE_CHANGED`, `INVALID_FILE_NAME`, `INVALID_DESTINATION`, `TRANSFER_LIMIT_REACHED`, `TRANSFER_QUEUE_FAILED`, `TRANSFER_CANCELLED`, `TRANSFER_TIMEOUT`, `TRANSFER_FAILED`, `TRANSFER_SIZE_MISMATCH`, `INTEGRITY_CHECK_FAILED`, `FILE_IO_ERROR`, `TASK_ALREADY_FINISHED`, `UNKNOWN_TASK`, and `INTERNAL_ERROR`.

## 8. Security boundary

Protocol v1 has no peer authentication, confidentiality or integrity protection. It is intended for local development and trusted LAN testing only. The default listening address is loopback (`127.0.0.1`). Do not expose this protocol to an untrusted network or the public Internet.
