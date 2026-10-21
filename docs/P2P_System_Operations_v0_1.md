# System Operation và System Event — P2P File Sharing

| Thuộc tính | Giá trị |
|---|---|
| Mã tài liệu | SSD-001 |
| Phiên bản | 0.1 — Draft |
| Ngày cập nhật | 21/10/2026 |
| Tài liệu nguồn | `P2P_File_Sharing_SRS_v0_2.md`, `P2P_File_Sharing_Use_Case_Analysis_v0_2.md`, `P2P_System_Sequence_Diagrams_v0_1.puml` |
| Trạng thái | Bản nháp để rà soát trước khi làm Domain Model |

## 1. Mục đích và quy ước

Tài liệu này tổng hợp các thông điệp xuất hiện trong System Sequence Diagram (SSD). Nó là cầu nối giữa phân tích yêu cầu và thiết kế: mỗi system operation sau này trở thành một phương thức ở lớp điều phối của Java core, và mỗi tham số hoặc giá trị trả về gợi ý một khái niệm cho Domain Model.

Quy ước dùng trong SSD:

- Hệ thống là **một hộp đen duy nhất**. React, Electron và Java không xuất hiện ở mức này.
- **System operation:** thông điệp từ một Actor vào hệ thống (mũi tên liền).
- **Giá trị trả về:** kết quả của một operation (mũi tên đứt).
- **System event:** thông báo bất đồng bộ từ hệ thống ra Actor (mũi tên mở). Event giữ cho giao diện đồng bộ với trạng thái thật do Java quản lý.
- Tên operation, tham số và mã lỗi ở đây là **mức phân tích**. Tên thông điệp giao thức thật sẽ được chốt trong `docs/protocol.md`.
- Mã lỗi `BACKEND_UNAVAILABLE` áp dụng cho mọi operation của Người dùng và không lặp lại trong từng dòng bên dưới.

## 2. System operation do Người dùng khởi tạo

| Operation | Tham số | Trả về | Lỗi chính | UC | Command nội bộ dự kiến | Yêu cầu |
|---|---|---|---|---|---|---|
| `startPeer` | `port`, `bindAddress` | `peerStatus` | `INVALID_PORT`, `PORT_IN_USE`, `PERMISSION_DENIED` | UC-01 | `peer.start` | FR-01, FR-02 |
| `stopPeer` | — | `peerStatus` | `CLEANUP_FAILED` | UC-02 | `peer.stop` | FR-03 |
| `connectPeer` | `host`, `port` | `connectionStatus` | `INVALID_ADDRESS`, `PEER_NOT_RUNNING`, `CONNECT_TIMEOUT`, `CONNECTION_REFUSED`, `HANDSHAKE_FAILED` | UC-03 | `peer.connect` | FR-04, FR-05 |
| `disconnectPeer` | `peerId` | `connectionStatus` | `UNKNOWN_PEER` | UC-04 | `peer.disconnect` | FR-06, FR-07 |
| `setSharedDirectory` | `path` | `sharedDirectory` | `INVALID_DIRECTORY`, `PERMISSION_DENIED` | UC-05 | `share.setDirectory` | FR-08 |
| `searchFiles` | `keyword` (tùy chọn), `peerIds` (tùy chọn) | `searchResult` | `INVALID_ARGUMENT` | UC-06, UC-06a | `files.search` | FR-09–FR-12 |
| `downloadFile` | `peerId`, `fileName`, `destinationDir` | `downloadAccepted` | `PEER_NOT_RUNNING`, `PEER_NOT_CONNECTED`, `INVALID_DESTINATION` | UC-07 | `transfer.download` | FR-13, FR-15–FR-19 |
| `cancelDownload` | `taskId` | `cancelRequested` | `TASK_ALREADY_FINISHED`, `UNKNOWN_TASK` | UC-07 (A5) | `transfer.cancel` | FR-19 |

Các command trong cột "Command nội bộ dự kiến" lấy từ các Activity Diagram đã vẽ. Riêng `files.search` là tên mới xuất hiện ở sơ đồ UC-06. Tất cả chỉ là dự kiến cho đến khi chốt hợp đồng JSON Lines.

Hai điểm đáng chú ý:

- `downloadFile` trả về ngay khi **chấp nhận** yêu cầu (kèm `taskId` và `targetFileName`), không chờ tải xong. Kết quả cuối đến qua event `transferStatusChanged`. Điều này khớp với NFR-04.
- `targetFileName` có thể khác `fileName` khi tên đã tồn tại ở thư mục đích (D-05), nên giao diện phải hiển thị tên do hệ thống trả về.

## 3. Thao tác truy vấn hỗ trợ đồng bộ trạng thái

Các thao tác này không gắn với một mục tiêu của người dùng, nhưng đã xuất hiện trong các Activity Diagram: Bridge dùng chúng để đồng bộ lại sau khi timeout thay vì gửi lệnh lặp lại (UC-01, UC-03, UC-05, UC-07), và khi backend khởi động lại (FR-24). Chúng chỉ đọc và lặp an toàn.

| Operation | Trả về | Dùng khi |
|---|---|---|
| `getPeerStatus` | `peerStatus` | Timeout sau `startPeer` hoặc `stopPeer`; backend vừa khởi động lại. |
| `listPeers` | danh sách `connectionStatus` | Timeout sau `connectPeer` hoặc `disconnectPeer`. |
| `getSharedDirectory` | `sharedDirectory` | Timeout sau `setSharedDirectory`. |
| `listTransfers` | danh sách trạng thái tác vụ | Timeout sau `downloadFile`; mở lại giao diện. |
| `shutdownApplication` | xác nhận | Người dùng đóng ứng dụng (FR-25). |

## 4. System operation do Peer từ xa khởi tạo

Ở các Use Case này hệ thống là **bên phục vụ**.

| Operation | Tham số | Trả về | Lỗi chính | UC | Yêu cầu |
|---|---|---|---|---|---|
| `handshake` | `remotePeerInfo` | `handshakeAccepted` hoặc `handshakeRejected` | `reason` | UC-12 | FR-30 |
| `requestFileList` | — | `fileList` | `NOT_SHARING` | UC-08 | FR-14 |
| `requestFile` | `fileName` | `fileMetadata` rồi luồng `fileData` | `INVALID_REQUEST`, `FILE_UNAVAILABLE` | UC-08 | FR-14, FR-18 |
| `cancelTransfer` | — | — | — | UC-08 | BR-08 |

`FILE_UNAVAILABLE` là mã lỗi chung cho cả không tồn tại, ngoài phạm vi chia sẻ và không đọc được (G-03).

## 5. Thông điệp hệ thống gửi đến Peer từ xa

| Thông điệp | Gửi khi | UC |
|---|---|---|
| `handshake` | Kết nối đi đến peer khác | UC-03 |
| `requestFileList` | Truy vấn danh sách tập tin | UC-06a |
| `requestFile` | Bắt đầu tải | UC-07 |
| `cancelTransfer` | Người dùng hủy tải | UC-07 (A5) |
| `closeConnection` | Người dùng ngắt kết nối | UC-04 |

**Nhận xét:** `handshake`, `requestFileList`, `requestFile` và `cancelTransfer` xuất hiện ở cả hai chiều (mục 4 và mục 5). Một peer vừa là client vừa là server của cùng một giao thức, nên thiết kế giao thức và các lớp xử lý thông điệp nên dùng chung cho cả hai vai trò.

## 6. System event (hệ thống → Người dùng)

| Event | Dữ liệu | Phát khi | UC / Yêu cầu |
|---|---|---|---|
| `peerStatusChanged` | `status`, `bindAddress`, `port` | Peer chuyển trạng thái, kể cả STARTING và STOPPING | UC-01, UC-02, FR-23 |
| `connectionStatusChanged` | `peerId`, `status` | Kết nối thay đổi, kể cả mất kết nối đột ngột và kết nối đến | UC-03, UC-04, UC-12, FR-07 |
| `transferProgress` | `taskId`, `receivedBytes`, `totalBytes` | Trong lúc tải, có giới hạn tần suất | UC-07, FR-15 |
| `transferStatusChanged` | `taskId`, `status`, `detail` | Tác vụ đổi trạng thái; trạng thái cuối là COMPLETED, FAILED hoặc CANCELLED | UC-02, UC-07 |
| `activityLogged` | `entry` | Có sự kiện đáng ghi nhận | UC-08, UC-09, FR-29 |
| `backendStatusChanged` | `status` | Java backend dừng hoặc khởi động lại | FR-24 |

Giá trị trả về của một operation và event tương ứng mang cùng thông tin trạng thái (ví dụ `connectionStatus` và `connectionStatusChanged`). Giá trị trả về phục vụ nơi gọi, còn event giữ cho mọi thành phần giao diện khớp với trạng thái thật do Java quản lý. UC-09 không có operation riêng: nhật ký hoạt động chỉ là luồng event `activityLogged`.

## 7. Khái niệm lộ ra từ SSD (đầu vào cho Domain Model)

| Khái niệm | Thuộc tính thấy được | Xuất hiện ở |
|---|---|---|
| Peer (cục bộ) | `status` (STOPPED, STARTING, RUNNING, STOPPING), `bindAddress`, `port` | `startPeer`, `stopPeer`, `peerStatus` |
| Kết nối tới peer khác | `peerId`, `host`, `port`, `status` (CONNECTED, DISCONNECTED) | `connectPeer`, `disconnectPeer`, `connectionStatus` |
| Thông tin peer trong handshake | `localPeerInfo`, `remotePeerInfo` (nội dung chưa chốt) | `handshake` |
| Thư mục chia sẻ | `path` | `setSharedDirectory`, `sharedDirectory` |
| Metadata tập tin | `fileName`, `size`, `sha256` | `fileList`, `fileMetadata` |
| Kết quả tìm kiếm | tập tin kèm `peerId`; danh sách lỗi theo peer | `searchResult` |
| Tác vụ tải | `taskId`, `peerId`, `fileName`, `targetFileName`, `destinationDir`, `status`, `receivedBytes`, `totalBytes`, `reason` | `downloadAccepted`, `transferProgress`, `transferStatusChanged` |
| Bản ghi nhật ký | `entry` | `activityLogged` |

`fileData(chunk)` không được xem là khái niệm miền ở giai đoạn này. Nó chỉ trở thành khái niệm riêng nếu triển khai chia phần (UC-10, UC-11).

## 8. Điểm cần xác nhận

1. `peerId` hiện là mã định danh mờ do hệ thống cấp. Cách xác định một peer (địa chỉ và cổng lắng nghe khai báo trong handshake hay cách khác) vẫn là vấn đề mở số 4 ở SRS mục 10.3.
2. Nội dung của `localPeerInfo` và `remotePeerInfo` (ví dụ cổng lắng nghe, phiên bản giao thức, tên hiển thị) sẽ chốt trong `docs/protocol.md`.
3. Các mã lỗi ở đây là mức phân tích. Khi viết `docs/protocol.md` cần ánh xạ chúng sang mã lỗi giao thức và mã lỗi của command nội bộ.
4. UC-10 và UC-11 (nâng cao) chưa có SSD. Chúng sẽ được vẽ nếu quyết định triển khai.
