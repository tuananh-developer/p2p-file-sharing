# P2P File Sharing

Ứng dụng desktop chia sẻ tập tin ngang hàng (Peer-to-Peer File Sharing). Java P2P Core chịu trách nhiệm quản lý peer và truyền thông trực tiếp qua TCP. Electron/React sẽ là lớp desktop/UI giao tiếp với Java qua JSON Lines trên `stdin`/`stdout`.

> **Trạng thái:** Java Core MVP đang được triển khai. Repo hiện chưa có mã nguồn Electron/React; các chức năng chia sẻ thư mục, tìm kiếm và truyền file thuộc giai đoạn tiếp theo, chưa được tuyên bố hoàn thành.

## Tech stack

- Java 21
- Gradle 9.8.1 (Gradle Wrapper)
- TCP/IP sockets
- JSON Lines bridge (Jackson)
- JUnit 5
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

Supported commands: `peer.start`, `peer.stop`, `peer.connect`, `peer.disconnect`, `peer.status`, `peer.list`, `app.shutdown`. Legacy operation names (`startPeer`, `stopPeer`, etc.) are accepted as aliases.

See [`docs/protocol.md`](docs/protocol.md) for the TCP handshake and connection framing contract.

## Security note

MVP does not authenticate peers or encrypt TCP traffic. Default bind address is `127.0.0.1`, which limits the listener to the local machine. Only choose a LAN bind address when testing on a trusted network. Do not expose the listener directly to the public Internet.

## Current MVP scope

- [x] Start/stop peer listener with explicit bind address and port
- [x] Outgoing TCP connection and protocol handshake
- [x] Incoming TCP connection and protocol handshake
- [x] Peer list, status events, and disconnect handling
- [x] Request-correlated JSON Lines responses and asynchronous events
- [x] Bounded connection and handshake timeout
- [x] Resource cleanup on peer stop / application shutdown
- [ ] Electron process lifecycle integration
- [ ] React UI and preload/IPC layer
- [ ] Shared directory, file listing/search, transfers and SHA-256 verification
