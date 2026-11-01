# P2P File Sharing

Ứng dụng desktop chia sẻ tập tin ngang hàng (Peer-to-Peer File Sharing). Java P2P Core chịu trách nhiệm quản lý peer và truyền thông trực tiếp qua TCP. Electron/React sẽ là lớp desktop/UI giao tiếp với Java qua JSON Lines trên `stdin`/`stdout`.

> **Trạng thái:** Java Core đã có vòng đời peer, kết nối TCP, file catalog và tải file một peer-một file với SHA-256. Repo hiện chưa có mã nguồn Electron/React; resume và tải đa nguồn chưa được triển khai.

## Tech stack

- Java 21
- Gradle 9.8.1 (Gradle Wrapper)
- TCP/IP sockets
- JSON Lines bridge (Jackson)
- JUnit Jupiter
- Frontend dự kiến: Electron, React, TypeScript

## Requirements

- Fedora Linux (hoặc Linux/macOS/Windows có Java 21 và quyền mở TCP port)
- JDK 21
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

Supported commands: `peer.start`, `peer.stop`, `peer.connect`, `peer.disconnect`, `peer.status`, `peer.list`, `share.setDirectory`, `share.getDirectory`, `files.listLocal`, `files.listRemote`, `files.search`, `transfer.download`, `transfer.cancel`, `transfer.list`, `app.shutdown`. Legacy operation names (`startPeer`, `stopPeer`, `setSharedDirectory`, `searchFiles`, `downloadFile`, etc.) are accepted as aliases.

See [`docs/protocol.md`](docs/protocol.md) for the TCP handshake and connection framing contract.

## Security note

MVP does not authenticate peers or encrypt TCP traffic. Any peer that can connect may query metadata and download files in the selected shared directory. Default bind address is `127.0.0.1`, which limits the listener to the local machine. Only choose a LAN bind address on a trusted network and share a directory whose contents you are comfortable exposing. Do not expose the listener directly to the public Internet.

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
- [x] Single-file transfers, progress reporting, cancellation and SHA-256 verification
- [ ] Electron process lifecycle integration
- [ ] React UI and preload/IPC layer
