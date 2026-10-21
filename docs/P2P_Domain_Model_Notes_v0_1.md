# Domain Model, State Machine và Glossary — P2P File Sharing

| Thuộc tính | Giá trị |
|---|---|
| Mã tài liệu | DM-001 |
| Phiên bản | 0.1 — Draft |
| Ngày cập nhật | 21/10/2026 |
| Tài liệu nguồn | `P2P_File_Sharing_SRS_v0_2.md`, `P2P_File_Sharing_Use_Case_Analysis_v0_2.md`, `P2P_System_Operations_v0_1.md` |
| Sơ đồ đi kèm | `P2P_Domain_Model_v0_1.puml`, `P2P_State_Machines_v0_1.puml` |
| Trạng thái | Bản nháp để rà soát trước khi chuyển sang thiết kế |

## 1. Mục đích và phạm vi

Domain Model mô tả các **khái niệm của bài toán** và quan hệ giữa chúng. Nó không mô tả lớp cài đặt, nên cố ý **không có**: React, Electron, IPC, JSON Lines, socket, luồng/executor, framing, mã lỗi giao thức, thông điệp handshake hay khối dữ liệu (`chunk`). Những thứ đó thuộc về thiết kế và sẽ xuất hiện ở Design Class Diagram.

Mô hình chỉ có thuộc tính và quan hệ, không có phương thức. Hành vi được mô tả bằng Use Case, SSD và State Machine.

## 2. Các lớp khái niệm

| Lớp | Ý nghĩa | Thuộc tính | Xuất hiện ở |
|---|---|---|---|
| `Peer` (cục bộ) | Instance đang chạy của ứng dụng trên máy này (SRS 1.4). | `status`, `bindAddress`, `port` | `startPeer`, `stopPeer`, `peerStatus` |
| `RemotePeer` | Một peer khác mà Peer cục bộ biết, qua kết nối đi hoặc đến. | `peerId`, `address`, `listeningPort` | `connectPeer`, `disconnectPeer`, `handshake` |
| `Connection` | Liên kết giữa Peer cục bộ và một RemotePeer. | `direction`, `status` | `connectionStatus`, UC-03, UC-04, UC-12 |
| `SharedDirectory` | Thư mục cục bộ được phép chia sẻ. | `path` | `setSharedDirectory` |
| `SharedFile` | Tập tin thường nằm ở cấp đầu của thư mục chia sẻ (D-01). | `fileName`, `size` | `requestFileList`, `requestFile` |
| `FileSearch` | Một lần làm mới danh sách hoặc tìm kiếm. | `keyword` (tùy chọn) | `searchFiles` |
| `PeerQuery` | Kết quả truy vấn danh sách đến một RemotePeer trong một FileSearch. | `outcome`, `failureReason` | `searchResult.peerErrors` (FR-12) |
| `RemoteFile` | Tập tin do một RemotePeer cung cấp, nhận từ danh sách. | `fileName`, `size` | `fileList`, `searchResult.files` |
| `TransferTask` (trừu tượng) | Một lần truyền tập tin giữa Peer cục bộ và một RemotePeer. | `taskId`, `fileName`, `totalBytes`, `transferredBytes`, `failureReason` | `transferProgress`, `transferStatusChanged` |
| `DownloadTask` | Tác vụ phía peer nhận. | `status`, `targetFileName`, `destinationDir`, `expectedSha256` | `downloadFile`, `cancelDownload` |
| `UploadTask` | Tác vụ phía peer nguồn khi phục vụ một yêu cầu tải. | `status` | UC-08, D-03 |
| `ActivityLogEntry` | Một sự kiện được ghi vào nhật ký hoạt động. | `timestamp`, `eventType`, `message` | `activityLogged`, UC-09 |

Giá trị của các kiểu liệt kê:

| Kiểu | Giá trị |
|---|---|
| `PeerStatus` | STOPPED, STARTING, RUNNING, STOPPING |
| `ConnectionStatus` | CONNECTING, CONNECTED, DISCONNECTED |
| `ConnectionDirection` | OUTGOING, INCOMING |
| `QueryOutcome` | SUCCESS, FAILED |
| `DownloadStatus` | PENDING, DOWNLOADING, VERIFYING, COMPLETED, FAILED, CANCELLED |
| `UploadStatus` | SERVING, COMPLETED, FAILED, CANCELLED |

## 3. Quan hệ và bội số

| Quan hệ | Bội số | Ý nghĩa |
|---|---|---|
| Peer — Connection | 1 — 0..* | Peer có thể giữ nhiều kết nối cùng lúc (BR-02, NFR-06). |
| Connection — RemotePeer | 0..* — 1 | Mỗi kết nối nối đến đúng một RemotePeer. |
| Peer — SharedDirectory | 1 — 0..1 | Chưa chọn thư mục thì chưa có SharedDirectory. |
| SharedDirectory ◆— SharedFile | 1 — 0..* | Hợp thành: SharedFile được suy ra từ nội dung thư mục, chỉ cấp đầu. |
| Peer — TransferTask | 1 — 0..* | Cả tác vụ tải lẫn tác vụ phục vụ. |
| TransferTask — RemotePeer | 0..* — 1 | Đối tác của tác vụ: peer nguồn với DownloadTask, peer yêu cầu với UploadTask. |
| TransferTask ◁— DownloadTask, UploadTask | tổng quát hóa | Hai loại tác vụ dùng chung thuộc tính tiến trình. |
| Peer — FileSearch | 1 — 0..* | Mỗi lần làm mới hoặc tìm kiếm là một FileSearch. |
| FileSearch ◆— PeerQuery | 1 — 0..* | Mỗi peer được truy vấn có một kết quả riêng. |
| PeerQuery — RemotePeer | 0..* — 1 | Peer được truy vấn. |
| PeerQuery ◆— RemoteFile | 1 — 0..* | Tập tin nhận được từ peer đó (sau khi lọc từ khóa nếu có). |
| Peer — ActivityLogEntry | 1 — 0..* | Nhật ký hoạt động của peer. |

## 4. Ràng buộc nghiệp vụ trên mô hình

| Mã | Ràng buộc | Nguồn |
|---|---|---|
| I1 | Mỗi RemotePeer có tối đa một Connection đang CONNECTED với Peer tại một thời điểm. Cách xác định "cùng một peer" còn mở. | UC-03 A3, UC-12 A3 |
| I2 | Peer có thể đồng thời có Connection OUTGOING và INCOMING. | BR-02 |
| I3 | Peer chỉ ở RUNNING khi đã bind cổng thành công, và chỉ chấp nhận Connection mới khi RUNNING. | BR-01, UC-12 A2 |
| I4 | Mọi SharedFile là tập tin thường nằm trực tiếp trong SharedDirectory. Không phục vụ tập tin nằm ngoài đó. | BR-03, D-01, D-02 |
| I5 | SharedDirectory chỉ tồn tại trong phiên chạy, không lưu qua lần khởi động lại. | D-04 |
| I6 | FileSearch chỉ tạo PeerQuery cho RemotePeer có Connection CONNECTED. | BR-04 |
| I7 | `targetFileName` của DownloadTask không trùng tập tin có sẵn ở `destinationDir` khi tạo, và không bao giờ ghi đè. | BR-07, D-05 |
| I8 | DownloadTask chỉ COMPLETED khi `transferredBytes` bằng `totalBytes` và SHA-256 khớp `expectedSha256`. | BR-06 |
| I9 | Khi một Connection kết thúc hoặc Peer dừng, mọi DownloadTask và UploadTask đang chạy phụ thuộc vào đó phải chuyển sang FAILED hoặc CANCELLED. Hai kiểu tác vụ đều có đủ hai trạng thái này. | BR-08, D-03 |
| I10 | Peer có thể có ít nhất hai TransferTask chạy đồng thời. | NFR-06 |

## 5. Máy trạng thái

Sơ đồ đầy đủ nằm trong `P2P_State_Machines_v0_1.puml`. Dưới đây là phần tóm tắt và các tương tác giữa các máy trạng thái.

| Máy trạng thái | Trạng thái | Ghi chú chính |
|---|---|---|
| Peer | STOPPED → STARTING → RUNNING → STOPPING → STOPPED | `startPeer` khi đã RUNNING và `stopPeer` khi đã STOPPED đều lặp an toàn. Bind thất bại quay về STOPPED và giải phóng tài nguyên. |
| Connection | CONNECTING → CONNECTED → DISCONNECTED | CONNECTING là trạng thái tạm thời, kết nối chỉ vào danh sách peer khi đã CONNECTED. |
| DownloadTask | PENDING → DOWNLOADING → VERIFYING → COMPLETED, nhánh FAILED và CANCELLED | VERIFYING không phụ thuộc kết nối vì dữ liệu đã nhận đủ. Tệp tạm bị xóa khi FAILED hoặc CANCELLED (G-01). |
| UploadTask | SERVING → COMPLETED, nhánh FAILED và CANCELLED | Yêu cầu không hợp lệ bị từ chối ngay và không tạo UploadTask. |

Tương tác giữa các máy trạng thái:

| Khi xảy ra | Tác động |
|---|---|
| Peer vào STOPPING (D-03) | Hủy mọi TransferTask đang chạy (CANCELLED), rồi các Connection chuyển DISCONNECTED. |
| Connection chuyển DISCONNECTED | TransferTask phụ thuộc chuyển FAILED hoặc CANCELLED (BR-08) và phát `connectionStatusChanged`. |
| TransferTask đổi trạng thái | Phát `transferStatusChanged`; trạng thái cuối được ghi vào nhật ký. |

## 6. Từ điển thuật ngữ (Glossary)

Bảng này mở rộng mục 1.4 của SRS. Cột "Tên trong mô hình" là tên dùng trong sơ đồ và sau này trong mã nguồn.

| Thuật ngữ | Tên trong mô hình | Định nghĩa |
|---|---|---|
| Peer, peer cục bộ | `Peer` | Instance đang chạy của ứng dụng trên máy này, vừa yêu cầu vừa cung cấp tập tin. |
| Peer từ xa | `RemotePeer` | Một peer khác mà peer cục bộ đã kết nối tới hoặc nhận kết nối từ. |
| Peer nguồn | vai trò | Peer đang cung cấp một tập tin cho peer khác tải. |
| Peer nhận | vai trò | Peer gửi yêu cầu và nhận tập tin. |
| Kết nối | `Connection` | Liên kết TCP đã hoàn tất handshake giữa hai peer. |
| Hướng kết nối | `ConnectionDirection` | OUTGOING nếu peer cục bộ chủ động kết nối đi (UC-03), INCOMING nếu nhận kết nối do peer từ xa khởi tạo (UC-12). |
| Handshake | khái niệm giao thức | Trao đổi thông điệp đầu tiên để hai peer chấp nhận nhau. Nội dung chốt trong `docs/protocol.md`, không phải lớp miền. |
| Thư mục chia sẻ | `SharedDirectory` | Thư mục cục bộ mà người dùng cho phép ứng dụng cung cấp tập tin từ đó. |
| Tập tin chia sẻ | `SharedFile` | Tập tin thường nằm trực tiếp trong thư mục chia sẻ. |
| Tập tin từ xa | `RemoteFile` | Tập tin do một peer từ xa cung cấp, nhìn thấy qua danh sách. |
| Metadata tập tin | thuộc tính | Tên và kích thước của tập tin. Giá trị SHA-256 chỉ có khi bắt đầu tải (G-02). |
| Tìm kiếm tập tin | `FileSearch` | Một lần làm mới danh sách hoặc lọc theo từ khóa trên các peer đang kết nối. |
| Truy vấn peer | `PeerQuery` | Kết quả lấy danh sách từ một peer trong một lần tìm kiếm. |
| Tác vụ truyền | `TransferTask` | Một lần truyền tập tin giữa hai peer, có tiến trình và trạng thái. |
| Tác vụ tải | `DownloadTask` | Tác vụ truyền xét từ phía peer nhận. |
| Tác vụ phục vụ | `UploadTask` | Tác vụ truyền xét từ phía peer nguồn. |
| Tệp tạm | khái niệm lưu trữ | Tệp `<tên đích>.part` nơi dữ liệu được ghi trước khi kiểm tra toàn vẹn (G-01). |
| Tên đích | `targetFileName` | Tên cuối cùng của tập tin đã tải. Khác tên gốc nếu tên đã tồn tại (D-05). |
| Kiểm tra toàn vẹn | UC-07a | So sánh số byte và SHA-256 của tập tin nhận được với giá trị do peer nguồn cung cấp. |
| Nhật ký hoạt động | `ActivityLogEntry` | Danh sách sự kiện quan trọng theo thời gian, không chứa nội dung tập tin. |
| System operation | khái niệm phân tích | Thông điệp từ một Actor vào hệ thống, xem `P2P_System_Operations_v0_1.md`. |
| System event | khái niệm phân tích | Thông báo bất đồng bộ từ hệ thống ra Actor. |
| `peerId` | `RemotePeer.peerId` | Mã định danh mờ do hệ thống cấp cho một peer từ xa. |
| `taskId` | `TransferTask.taskId` | Mã định danh của một tác vụ truyền. |

## 7. Quyết định mô hình hóa

1. **Tách `RemotePeer` và `Connection`.** Kết nối có thể kết thúc nhưng peer vẫn là đối tác của tác vụ và kết quả tìm kiếm. Tách ra cũng làm rõ vấn đề định danh peer. Nếu thấy quá chi tiết, có thể gộp thành một lớp ở bước thiết kế.
2. **Có `UploadTask` và lớp trừu tượng `TransferTask`.** D-03 và BR-08 đều nói đến tác vụ "truyền nhận", nên phía phục vụ cũng phải có đối tượng quản lý. Tổng quát hóa có thể bỏ khi thiết kế nếu không cần.
3. **`FileSearch` và `PeerQuery`.** Cần để mô hình hóa lỗi riêng theo từng peer (FR-12) mà không làm hỏng kết quả của peer khác.
4. **`RemoteFile` không có `sha256`.** Danh sách chỉ trả tên và kích thước (UC-06a). SHA-256 chỉ có ở bước `fileMetadata` khi bắt đầu tải.
5. **Không có lớp cho Người dùng.** Người dùng là Actor, nằm ngoài miền được mô hình hóa.

## 8. Điểm cần xác nhận

Bốn điểm mới phát sinh từ State Machine đã được thêm vào SRS mục 10.3 (mục 8 đến 11):

1. Hành vi `startPeer` hoặc `stopPeer` khi Peer đang STARTING hoặc STOPPING.
2. Cách xử lý khi dọn dẹp lỗi lúc dừng peer (hiện sơ đồ để Peer ở lại STOPPING).
3. `cancelDownload` khi tác vụ đang VERIFYING.
4. Tác vụ phụ thuộc chuyển CANCELLED hay FAILED khi kết nối kết thúc.

Ngoài ra vẫn còn mở: cách xác định "cùng một peer" (SRS mục 10.3, mục 4), và hành vi khi đổi thư mục chia sẻ lúc đang có `UploadTask` (mục 5).
