# P2P File Sharing

Ứng dụng desktop chia sẻ tập tin ngang hàng (Peer-to-Peer File Sharing). Electron Main/Preload quản lý Java process và IPC; React chỉ gọi API được expose an toàn qua preload. Java Core tiếp tục chịu trách nhiệm xử lý P2P.

> **Trạng thái:** Java Core hỗ trợ P2P TCP/TLS cho LAN hoặc địa chỉ có thể truy cập trực tiếp, catalog file, tải nhiều nguồn theo range, SHA-256 và khôi phục download sau restart. Desktop còn có đường Internet WebRTC DataChannel: mời peer bằng room code, ICE/STUN, TURN fallback, xác minh mã bảo mật từ DTLS fingerprint và truyền file theo chunk có ACK + SHA-256. Public Internet yêu cầu triển khai signaling HTTPS; NAT nghiêm ngặt yêu cầu TURN. WebRTC resume và kiểm thử thực tế qua nhiều mạng vẫn chưa hoàn tất.

## Tech stack

- Java 27
- Gradle 9.8.2 (Gradle Wrapper)
- TCP/IP sockets (LAN transport)
- WebRTC DataChannels with ICE/STUN/TURN (Internet transport)
- JSON Lines bridge (Jackson)
- JUnit Jupiter
- Desktop UI: Electron 45, React 19, TypeScript 6, Vite 8

## Requirements

- Fedora Linux (hoặc Linux/macOS/Windows có Java 27 và quyền mở TCP port)
- JDK 27 (Temurin hoặc OpenJDK tương thích)
- Node.js 24+ và pnpm 12.17.0
- Không cần database hoặc dịch vụ cloud cho MVP

Kiểm tra Java:

```bash
java -version
./gradlew --version
```

## Build and test

```bash
./gradlew clean test
./gradlew build
```

Cài pnpm 12.17.0 trên Linux/macOS:

```bash
curl -fsSL https://get.pnpm.io/install.sh | env PNPM_VERSION=12.17.0 sh -
```

## Run Java P2P Core

Core chạy như một tiến trình độc lập. Mỗi dòng stdin là một command JSON; stdout chỉ chứa response/event JSON Lines. Log chẩn đoán được ghi ra stderr.

```bash
./gradlew installDist
./build/install/file_sharing/bin/file_sharing
```

Nên chạy executable đã đóng gói thay vì `./gradlew run` khi kết nối với Electron, vì Gradle có thể ghi log build ra stdout trong khi stdout của Java Core được dành riêng cho JSON Lines.

Ví dụ khởi chạy peer trên loopback port `5001`:

```json
{"id":"start-1","command":"peer.start","payload":{"bindAddress":"127.0.0.1","port":5001}}
```

Liệt kê peer đã biết:

```json
{"id":"list-1","command":"peer.list","payload":{}}
```

Chọn thư mục chia sẻ (cấu hình chỉ tồn tại trong phiên chạy Java hiện tại):

```json
{"id":"share-1","command":"share.setDirectory","payload":{"path":"/home/user/Shared"}}
```

Lấy trạng thái thư mục chia sẻ:

```json
{"id":"share-status-1","command":"share.getDirectory","payload":{}}
```

Liệt kê file local ở cấp đầu của thư mục đã chọn:

```json
{"id":"files-local-1","command":"files.listLocal","payload":{}}
```

Lấy danh sách file từ một peer đã kết nối (thay UUID bằng `peerId` trong kết quả `peer.list`):

```json
{"id":"files-remote-1","command":"files.listRemote","payload":{"peerId":"<peer-uuid>"}}
```

Tìm theo tên trên tất cả peer đang kết nối; có thể truyền `peerIds` để giới hạn nguồn:

```json
{"id":"search-1","command":"files.search","payload":{"keyword":"report"}}
```

Kết quả `files.search` gồm `results` và `errors` riêng theo peer. Một peer không chia sẻ hoặc không phản hồi không làm mất kết quả lấy được từ các peer khác.

Tải file từ một peer về thư mục đích (lệnh trả về ngay `taskId`, trạng thái tiếp tục được gửi qua event):

```json
{"id":"download-1","command":"transfer.download","payload":{"peerId":"<peer-uuid>","fileName":"report.pdf","destinationDir":"/home/user/Downloads"}}
```

Theo dõi hoặc hủy tác vụ:

```json
{"id":"transfers-1","command":"transfer.list","payload":{}}
{"id":"cancel-1","command":"transfer.cancel","payload":{"taskId":"<task-uuid>"}}
```

File được ghi vào `.part`, xác minh kích thước và SHA-256 rồi mới đổi tên. Tên đích trùng được tự động đổi thành `name (1).ext`; file hiện hữu không bị ghi đè.


Tải file theo nhiều nguồn: truyền danh sách peerIds có cùng file. Java sẽ kiểm tra SHA-256 giữa các nguồn, chia file thành các range 4 MiB, tải tối đa tám range đồng thời và tự thử peer khác nếu một nguồn lỗi:

```json
{"id":"multi-download-1","command":"transfer.downloadMulti","payload":{"peerIds":["<peer-uuid-1>","<peer-uuid-2>"],"fileName":"dataset.bin","destinationDir":"/home/user/Downloads"}}
```

Các peer phải trả về cùng kích thước và SHA-256; hệ thống không ghép dữ liệu từ hai file khác nội dung chỉ vì trùng tên. Tất cả các range phải đầy đủ và hash của file sau ghép phải hợp lệ trước khi đổi tên file tạm.

Kết nối đến peer khác:

```json
{"id":"connect-1","command":"peer.connect","payload":{"host":"127.0.0.1","port":5002}}
```

Ngắt kết nối theo `peerId` nhận được từ response/event:

```json
{"id":"disconnect-1","command":"peer.disconnect","payload":{"peerId":"<peer-uuid>"}}
```

Dừng peer hoặc yêu cầu Java process kết thúc:

```json
{"id":"stop-1","command":"peer.stop","payload":{}}
{"id":"shutdown-1","command":"app.shutdown","payload":{}}
```

`app.shutdown` dừng peer, đóng tài nguyên và kết thúc vòng lặp bridge. Electron nên gửi command này trước khi kết thúc tiến trình Java, sau đó đóng stdin/process và xử lý timeout ở phía Electron.

## Run the desktop app

Cài dependencies frontend, tạo Gradle distribution cho Java Core, sau đó chạy Electron + Vite:

```bash
pnpm install
./gradlew --no-daemon installDist
pnpm run dev
```

Lệnh `pnpm run dev` compile Electron Main/Preload, khởi chạy Vite ở loopback và mở cửa sổ desktop. Java backend được Electron tự khởi động từ `build/install/file_sharing/lib`; cần có JDK 27 trong `JAVA_HOME` hoặc `PATH`.

Kiểm tra và build:

```bash
pnpm run typecheck
pnpm test
pnpm run build
```

Tạo thư mục ứng dụng Linux (cần chạy `installDist` trước):

```bash
pnpm run package:linux
```

Có thể override executable Java hoặc thư mục thư viện backend khi phát triển bằng `P2P_JAVA_EXECUTABLE` và `P2P_BACKEND_LIB_DIR`. Renderer không nhận đường dẫn đích tải file tùy ý: hộp thoại hệ thống chọn thư mục được mở từ Electron Main.

### Desktop security and process boundary

- BrowserWindow bật `contextIsolation`, `sandbox`, `webSecurity` và tắt `nodeIntegration`.
- Preload chỉ expose các thao tác P2P có allowlist; Renderer không được gọi arbitrary IPC, `child_process`, filesystem hoặc socket.
- Electron Main khởi chạy Java bằng `spawn` với `shell: false`; stdout dành riêng cho JSON Lines và stderr dành cho log.
- Response được ghép theo request ID. Event Java được chuyển tiếp đến React; process exit làm backend state chuyển sang FAILED thay vì giữ trạng thái peer đang chạy cũ.
- Khi đóng ứng dụng, Electron gửi `app.shutdown`, chờ process thoát trong thời hạn hữu hạn rồi mới dùng tín hiệu terminate/kill dự phòng.

Xem [`docs/desktop-architecture.md`](docs/desktop-architecture.md) để biết thêm về bridge, IPC và quy trình shutdown.

## JSON Lines bridge contract

Command envelope:

```json
{"id":"request-id","command":"peer.start","payload":{"bindAddress":"127.0.0.1","port":5001}}
```

Success response:

```json
{"type":"response","id":"request-id","success":true,"data":{"peer":{"peerId":"...","status":"RUNNING","bindAddress":"127.0.0.1","port":5001}}}
```

Error response:

```json
{"type":"response","id":"request-id","success":false,"error":{"code":"PORT_IN_USE","message":"Port 5001 is already in use."}}
```

Asynchronous event:

```json
{"type":"event","event":"connectionStatusChanged","data":{"peerId":"...","status":"CONNECTED"}}
```

Supported commands: `peer.start`, `peer.stop`, `peer.connect`, `peer.disconnect`, `peer.status`, `peer.list`, `share.setDirectory`, `share.getDirectory`, `files.listLocal`, `files.listRemote`, `files.search`, `transfer.download`, `transfer.downloadMulti`, `transfer.cancel`, `transfer.list`, `app.shutdown`. Legacy operation names (`startPeer`, `stopPeer`, `setSharedDirectory`, `searchFiles`, `downloadFile`, etc.) are accepted as aliases.

See [`docs/protocol.md`](docs/protocol.md) for the TCP handshake and connection framing contract.

## Peer security and network configuration

- Peer transport uses **TLS 1.3 with mutual certificate authentication**. Each installation creates and persists an RSA-3072 self-signed identity certificate; its SHA-256 fingerprint is the peer identity pin.
- A new peer is rejected until **both devices trust each other's fingerprint**. Exchange the fingerprint through a separate trusted channel (in person, a verified call, or another authenticated channel). Do not approve a fingerprint received only over the connection being authenticated.
- The local listener defaults to **Bind address `0.0.0.0` (Auto)** and **Listening port `0` (Auto)**. The OS allocates a free port, and the app lists active non-loopback IPv4 addresses that other peers can use. Enter a specific local address and/or port to use manual settings.
- Auto address listens on all local IPv4 interfaces. When using Wi-Fi, VPN, Docker, or a VM bridge, select the address reachable by the other device. The remote IP and the allocated listening port still need to be entered in the Connect to peer form; peer discovery is not automatic.
- Only share folders whose contents you are comfortable exposing to the devices you explicitly trust. TLS protects confidentiality and integrity in transit and authenticates pinned peers, but does not prevent denial-of-service or make a public listener safe. Prefer a trusted LAN or VM network and do not forward the peer port from your router to the public Internet.

Before building a packaged desktop release, copy `.env.example` to `.env` and set `VITE_SIGNALING_URL` to the deployed public HTTPS signaling URL. During development, the URL can also be entered in the Internet Connectivity panel. Never put TURN shared secrets or other server credentials in the desktop `.env`; TURN credentials are minted by the server for each room.

## Internet peer connectivity

The desktop app includes a WebRTC DataChannel path for peers that cannot accept inbound TCP connections:

1. Deploy the service in [infra/nat-traversal](infra/nat-traversal) on a public VPS with HTTPS and configure TURN for restrictive NATs.
2. Start the local peer in both desktop apps.
3. The first user creates an invite using the deployed signaling URL and shares the complete invite privately. It contains a short-lived room bearer secret.
4. The second user pastes the invite and joins. Both apps negotiate ICE candidates; WebRTC attempts a direct path and uses TURN when available and needed.
5. Compare the eight-digit DTLS safety code over a separate trusted channel. Both users must confirm before file operations are enabled.
6. The sender chooses a file from the configured shared directory. The recipient accepts and selects a save path. Chunks are acknowledged in a bounded window and the received file is finalized only after SHA-256 verification.

The signaling service relays only room metadata and SDP messages; file bytes travel over the WebRTC DataChannel, directly when ICE succeeds or through TURN when relay is needed. Invite codes are bearer secrets. Do not send them in public channels. The public STUN fallback is best-effort; a TURN deployment is required for networks where direct ICE cannot succeed. Internet transfers currently do not support resume after interruption.

Deployment and security details: [docs/internet-connectivity.md](docs/internet-connectivity.md) and [infra/nat-traversal/README.md](infra/nat-traversal/README.md).

## Current MVP scope

- [x] Start/stop peer listener with explicit bind address and port
- [x] Outgoing TCP connection and protocol handshake
- [x] Incoming TCP connection and protocol handshake
- [x] Peer list, status events, and disconnect handling
- [x] Request-correlated JSON Lines responses and asynchronous events
- [x] Bounded connection and handshake timeout
- [x] Resource cleanup on peer stop / application shutdown
- [x] Session-scoped shared directory selection and safe top-level file listing
- [x] Remote file-list requests, case-insensitive search and per-peer error isolation
- [x] Single-source and multi-source ranged transfers, per-range progress, cancellation and final SHA-256 verification
- [x] Electron Java process lifecycle, startup readiness, correlated responses and shutdown cleanup
- [x] Allowlisted preload/IPC boundary and backend-failure events
- [x] React UI for peer lifecycle, shared directory, search, download progress and cancellation
- [x] Split files into 4 MiB ranges and transfer ranges from multiple peers (FR-26–FR-27)
- [x] Persistent resume after restart (FR-28)
- [x] Session activity log for peer and transfer lifecycle (FR-29)
- [ ] Verify desktop packaging on Fedora through a full manual multi-peer LAN test
- [x] Add WebRTC signaling and DataChannel file streaming path for Internet peers
- [ ] Run manual cross-network tests against a deployed public signaling/TURN service
- [ ] Add resumable WebRTC transfers
