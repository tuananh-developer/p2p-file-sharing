# PHÂN TÍCH USE CASE — P2P FILE SHARING

| Thuộc tính | Giá trị |
|---|---|
| Dự án | Ứng dụng chia sẻ tập tin ngang hàng (P2P File Sharing Desktop Application) |
| Phiên bản | 0.3 — Bản hiện tại, đối chiếu mã nguồn |
| Ngày cập nhật | 27/11/2026 |
| Cơ sở | SRS v0.2 cho các chức năng P2P/TCP ban đầu; mã nguồn hiện tại trên nhánh \`main\` cho các chức năng đã bổ sung |
| Trạng thái | Bản Use Case thống nhất dùng cho phân tích và báo cáo |

## 1. Mục đích và phạm vi

Tài liệu xác định tác nhân, ranh giới hệ thống, các Use Case và luồng tương tác của ứng dụng P2P File Sharing. Nội dung được đối chiếu với mã nguồn hiện tại, bao gồm Java P2P Core, Electron Main/Preload, React UI và thành phần WebRTC phía Desktop.

Một instance ứng dụng là hệ thống đang được mô hình hóa. Instance ứng dụng khác là **Peer từ xa**, nằm ngoài ranh giới hệ thống. Với kết nối LAN, nội dung tệp được trao đổi trực tiếp giữa các peer qua TCP/TLS. Với kết nối Internet, hai peer thiết lập WebRTC DataChannel; Signaling Server chỉ chuyển tiếp thông tin thiết lập phiên, không lưu trữ nội dung tệp.

Các thành phần React UI, Electron Main/Preload và Java P2P Core nằm bên trong ranh giới hệ thống, không phải Actor. Socket, hệ thống tệp và hệ điều hành là tài nguyên/công nghệ, không phải Actor nghiệp vụ.

## 2. Tác nhân (Actors)

### A-01 — Người dùng cục bộ

Người đang thao tác trên ứng dụng Desktop. Người dùng có thể khởi chạy/dừng peer, kết nối thiết bị khác, chọn thư mục chia sẻ, tìm kiếm và tải tệp, quản lý fingerprint tin cậy, tạo/tham gia phiên Internet và theo dõi tác vụ.

### A-02 — Peer từ xa

Một instance P2P File Sharing khác. Peer từ xa có thể chủ động kết nối đến peer hiện tại, nhận kết nối do peer hiện tại khởi tạo, cung cấp danh sách/tệp được chia sẻ hoặc gửi/nhận tệp qua phiên Internet.

### A-03 — Signaling Server

Dịch vụ bên ngoài hỗ trợ tạo phòng kết nối tạm thời và chuyển tiếp thông tin signaling như SDP/ICE giữa hai peer. Dịch vụ không nhận hoặc lưu nội dung tệp.

### A-04 — Dịch vụ ICE (STUN/TURN)

Hạ tầng bên ngoài được WebRTC sử dụng để thu thập và lựa chọn ứng viên kết nối. STUN hỗ trợ phát hiện địa chỉ phản xạ; TURN chuyển tiếp lưu lượng khi kết nối trực tiếp không khả dụng. TURN chỉ hoạt động khi có dịch vụ TURN được triển khai và truy cập được.

## 3. Danh sách Use Case

| Mã | Tên Use Case | Actor chính | Actor hỗ trợ | Mức triển khai |
|---|---|---|---|---|
| UC-01 | Khởi chạy peer | Người dùng cục bộ | — | Đã triển khai |
| UC-02 | Dừng peer | Người dùng cục bộ | — | Đã triển khai |
| UC-03 | Kết nối peer | Người dùng cục bộ | Peer từ xa | Đã triển khai |
| UC-04 | Ngắt kết nối peer | Người dùng cục bộ | Peer từ xa | Đã triển khai |
| UC-05 | Chọn thư mục chia sẻ | Người dùng cục bộ | — | Đã triển khai |
| UC-06 | Xem/tìm kiếm tập tin | Người dùng cục bộ | Peer từ xa | Đã triển khai |
| UC-06a | Lấy danh sách tập tin từ peer | Người dùng cục bộ (qua UC-06) | Peer từ xa | Đã triển khai |
| UC-07 | Tải tập tin qua LAN | Người dùng cục bộ | Peer từ xa (nguồn) | Đã triển khai |
| UC-07a | Kiểm tra tính toàn vẹn | Được UC-07/UC-18 gọi | — | Đã triển khai |
| UC-08 | Cung cấp tập tin cho peer | Peer từ xa | — | Đã triển khai |
| UC-09 | Theo dõi nhật ký hoạt động | Người dùng cục bộ | — | Đã triển khai |
| UC-10 | Tiếp tục tải tập tin LAN bị gián đoạn | Người dùng cục bộ | Peer từ xa | Đã triển khai |
| UC-11 | Tải tập tin từ nhiều peer | Người dùng cục bộ | Các peer từ xa | Đã triển khai |
| UC-12 | Chấp nhận kết nối đến | Peer từ xa | — | Đã triển khai |
| UC-13 | Quản lý fingerprint peer tin cậy | Người dùng cục bộ | — | Đã triển khai |
| UC-14 | Tạo lời mời kết nối Internet | Người dùng cục bộ | Signaling Server, Dịch vụ ICE, Peer từ xa | Có mã nguồn; kiểm thử xuyên mạng còn cần xác nhận |
| UC-15 | Tham gia lời mời kết nối Internet | Người dùng cục bộ | Signaling Server, Dịch vụ ICE, Peer từ xa | Có mã nguồn; kiểm thử xuyên mạng còn cần xác nhận |
| UC-16 | Xác nhận mã an toàn của peer Internet | Người dùng cục bộ | Peer từ xa | Đã triển khai ở phía ứng dụng |
| UC-17 | Gửi tập tin qua Internet | Người dùng cục bộ | Peer từ xa | Có mã nguồn; kiểm thử xuyên mạng còn cần xác nhận |
| UC-18 | Tiếp nhận tập tin qua Internet | Người dùng cục bộ | Peer từ xa | Có mã nguồn; kiểm thử xuyên mạng còn cần xác nhận |
| UC-19 | Hủy tác vụ truyền tập tin | Người dùng cục bộ | Peer từ xa (đối với Internet) | Đã triển khai |
| UC-20 | Đóng phiên kết nối Internet | Người dùng cục bộ | Peer từ xa | Đã triển khai ở phía ứng dụng |

**Lưu ý về phạm vi:** UC-01 đến UC-13 mô tả các chức năng peer/TCP và quản lý cục bộ. UC-14 đến UC-20 bổ sung các chức năng Internet/WebRTC đang có trong mã nguồn Desktop. Việc có mã nguồn phía client không đồng nghĩa đã kiểm thử thành công qua mọi mạng NAT/Firewall; triển khai Signaling HTTPS/TURN công khai và kiểm thử trên các mạng độc lập vẫn là công việc cần xác nhận.

## 4. Quan hệ giữa các Use Case

1. UC-06 **include** UC-06a: khi làm mới/tìm kiếm, hệ thống truy vấn metadata tệp từ các peer đang kết nối.
2. UC-07 **include** UC-07a: chỉ đánh dấu tải hoàn tất sau khi kiểm tra kích thước và SHA-256.
3. UC-18 **include** UC-07a: tệp nhận qua Internet cũng phải được kiểm tra tính toàn vẹn trước khi công bố tại vị trí đích.
4. UC-10 **extend** UC-07: tiếp tục tải là luồng mở rộng khi đã có dữ liệu tải LAN dở và manifest hợp lệ.
5. UC-11 **extend** UC-07: tải nhiều nguồn là biến thể của thao tác tải khi có nhiều peer có cùng nội dung.
6. UC-19 **extend** UC-07, UC-17 và UC-18: người dùng có thể hủy một tác vụ truyền đang hoạt động.
7. UC-03 và UC-12 là hai hướng kết nối độc lập: UC-03 là kết nối đi do người dùng khởi tạo; UC-12 là kết nối đến do peer từ xa khởi tạo.
8. UC-14 và UC-15 đều tương tác với Signaling Server để tạo/tham gia phòng. Dịch vụ ICE (STUN/TURN) hỗ trợ quá trình thiết lập đường truyền WebRTC.
9. UC-16 là điều kiện bắt buộc trước khi UC-17/UC-18 được phép trao đổi tệp: cả hai bên phải xác nhận cùng mã an toàn.
10. Không dùng \`include\` chỉ để mô tả thứ tự thao tác; quan hệ chỉ được dùng khi hành vi bắt buộc là một phần của Use Case cơ sở.

## 5. Đặc tả các Use Case

### UC-01 — Khởi chạy peer

- **Actor chính:** Người dùng cục bộ.
- **Tiền điều kiện:** Ứng dụng Desktop đã mở và Java backend ở trạng thái READY.
- **Kích hoạt:** Người dùng nhập địa chỉ bind/cổng và chọn **Start peer**.
- **Luồng chính:**
  1. Người dùng nhập địa chỉ bind và cổng lắng nghe; cổng 0 cho phép hệ điều hành cấp cổng trống.
  2. Electron chuyển yêu cầu \`peer.start\` đến Java Core.
  3. Java khởi tạo TLS listener, bind địa chỉ/cổng và bắt đầu accept kết nối.
  4. Hệ thống trả về peer ID, địa chỉ mạng và cổng thực tế.
  5. Giao diện hiển thị peer ở trạng thái RUNNING.
- **Ngoại lệ:** Địa chỉ/cổng không hợp lệ, cổng đang được sử dụng, thiếu quyền hoặc Java backend lỗi → không chuyển peer sang RUNNING và hiển thị lỗi.
- **Hậu điều kiện:** Peer lắng nghe kết nối đến trên địa chỉ/cổng đã chọn.

### UC-02 — Dừng peer

- **Actor chính:** Người dùng cục bộ.
- **Tiền điều kiện:** Peer đang RUNNING.
- **Kích hoạt:** Người dùng chọn **Stop**.
- **Luồng chính:**
  1. Electron gửi lệnh \`peer.stop\`.
  2. Java ngừng nhận kết nối mới và đóng listener.
  3. Các tác vụ truyền LAN đang chạy bị hủy theo chính sách dừng peer.
  4. Các kết nối và tài nguyên liên quan được đóng.
  5. Giao diện cập nhật trạng thái STOPPED.
- **Ngoại lệ:** Nếu quá trình giải phóng tài nguyên không hoàn tất, hệ thống báo lỗi dọn dẹp và không giả định peer đã dừng hoàn toàn.
- **Hậu điều kiện:** Listener và các kết nối do peer quản lý được đóng trong điều kiện bình thường.

### UC-03 — Kết nối peer

- **Actor chính:** Người dùng cục bộ.
- **Actor hỗ trợ:** Peer từ xa.
- **Tiền điều kiện:** Peer cục bộ đang RUNNING; người dùng biết host/IP và cổng của peer đích; hai peer đã tin cậy fingerprint chứng chỉ của nhau.
- **Kích hoạt:** Người dùng nhập host/IP, cổng và chọn **Connect**.
- **Luồng chính:**
  1. Giao diện gửi yêu cầu \`peer.connect\`.
  2. Java tạo kết nối TCP và thực hiện TLS 1.3 mutual authentication.
  3. Hai peer trao đổi handshake gồm peer ID và cổng lắng nghe.
  4. Java kiểm tra peer ID, đăng ký kết nối và phát sự kiện trạng thái.
  5. Giao diện hiển thị peer đã kết nối.
- **Ngoại lệ:** Không thể phân giải host, từ chối kết nối, timeout, handshake sai hoặc fingerprint chưa được tin cậy → đóng socket và hiển thị lỗi.
- **Hậu điều kiện:** Peer từ xa được ghi nhận trong danh sách peer nếu handshake thành công.

### UC-04 — Ngắt kết nối peer

- **Actor chính:** Người dùng cục bộ.
- **Actor hỗ trợ:** Peer từ xa.
- **Tiền điều kiện:** Peer đích đang được quản lý trong danh sách peer.
- **Kích hoạt:** Người dùng chọn **Disconnect** tại peer tương ứng.
- **Luồng chính:** Hệ thống đóng kết nối, giải phóng tài nguyên, phát sự kiện và cập nhật trạng thái peer thành DISCONNECTED.
- **Ngoại lệ:** Nếu peer đã mất kết nối, hệ thống đồng bộ trạng thái mà không làm ứng dụng lỗi.
- **Hậu điều kiện:** Kết nối không còn được xem là đang hoạt động.

### UC-05 — Chọn thư mục chia sẻ

- **Actor chính:** Người dùng cục bộ.
- **Tiền điều kiện:** Java backend sẵn sàng.
- **Kích hoạt:** Người dùng chọn **Add shared folder**.
- **Luồng chính:**
  1. Electron mở hộp thoại chọn thư mục của hệ điều hành.
  2. Người dùng chọn thư mục.
  3. Đường dẫn được gửi qua lệnh \`share.setDirectory\`.
  4. Java kiểm tra thư mục và cập nhật cấu hình chia sẻ.
  5. Giao diện hiển thị đường dẫn và danh sách tệp cấp đầu.
- **Ngoại lệ:** Người dùng hủy hộp thoại, thư mục không tồn tại hoặc không thể truy cập → không thay đổi cấu hình cũ.
- **Hậu điều kiện:** Thư mục được cấu hình làm nguồn chia sẻ trong phiên chạy hiện tại.
- **Giới hạn:** Chỉ chia sẻ tệp thường ở cấp đầu, không duyệt đệ quy; cấu hình thư mục chia sẻ không được lưu qua lần khởi chạy Java Core tiếp theo.

### UC-06 — Xem/tìm kiếm tập tin

- **Actor chính:** Người dùng cục bộ.
- **Actor hỗ trợ:** Các peer từ xa đang kết nối.
- **Tiền điều kiện:** Có ít nhất một peer TCP đang kết nối để truy vấn dữ liệu từ xa.
- **Kích hoạt:** Người dùng nhập từ khóa và chọn **Search devices**.
- **Luồng chính:**
  1. Giao diện gửi từ khóa qua \`files.search\`.
  2. Java truy vấn danh sách tệp từ các peer đang kết nối (UC-06a).
  3. Java lọc tên tệp theo từ khóa, tổng hợp kết quả và gắn với peer nguồn.
  4. Giao diện hiển thị tên tệp, thiết bị nguồn và kích thước.
- **Ngoại lệ:** Không có peer kết nối → hiển thị trạng thái rỗng; peer riêng lẻ không phản hồi → báo lỗi nguồn đó nhưng vẫn giữ kết quả từ peer khác.
- **Hậu điều kiện:** Người dùng nhận được danh sách kết quả có thể tải.
- **Giới hạn:** Chỉ tìm trong các peer đang kết nối trực tiếp, không phải tìm kiếm toàn mạng Internet.

### UC-06a — Lấy danh sách tập tin từ peer

- **Actor chính:** Người dùng cục bộ thông qua UC-06.
- **Actor hỗ trợ:** Peer từ xa.
- **Tiền điều kiện:** Peer đích đang CONNECTED.
- **Luồng chính:** Java gửi yêu cầu danh sách; peer từ xa trả metadata tên và kích thước của các tệp được chia sẻ; Java kiểm tra dữ liệu và gắn từng tệp với peer nguồn.
- **Ngoại lệ:** Peer không phản hồi hoặc trả metadata không hợp lệ → ghi nhận lỗi riêng cho peer.
- **Hậu điều kiện:** Danh sách metadata của peer được trả về, không truyền nội dung tệp ở bước này.

### UC-07 — Tải tập tin qua LAN

- **Actor chính:** Người dùng cục bộ.
- **Actor hỗ trợ:** Peer từ xa (nguồn).
- **Tiền điều kiện:** Peer cục bộ đang RUNNING; tệp xuất hiện trong kết quả tìm kiếm và nguồn đang kết nối.
- **Kích hoạt:** Người dùng chọn **Download**.
- **Luồng chính:**
  1. Người dùng chọn tệp và thư mục đích.
  2. Hệ thống kiểm tra tên đích; nếu trùng tên thì tự tạo tên mới, không ghi đè.
  3. Java yêu cầu metadata nguồn gồm tên, kích thước và SHA-256.
  4. Java tạo tác vụ tải, ghi dữ liệu vào tệp tạm và phát tiến trình về giao diện.
  5. Dữ liệu được truyền theo các range; hệ thống quản lý worker và trạng thái từng range.
  6. Khi nhận đủ dữ liệu, UC-07a kiểm tra kích thước và SHA-256.
  7. Nếu hợp lệ, tệp được hoàn tất tại vị trí đích và giao diện hiển thị COMPLETED.
- **Ngoại lệ:** Tệp nguồn không còn tồn tại, nguồn ngắt kết nối, không đủ dung lượng/không có quyền ghi hoặc dữ liệu không hợp lệ → tác vụ FAILED hoặc được giữ ở trạng thái PAUSED nếu đủ điều kiện phục hồi.
- **Hậu điều kiện:** Chỉ tệp đã xác minh mới được công bố là hoàn tất.

### UC-07a — Kiểm tra tính toàn vẹn

- **Actor:** Không có tương tác trực tiếp; được UC-07 hoặc UC-18 gọi.
- **Tiền điều kiện:** Đã nhận đủ dữ liệu và có kích thước/hash kỳ vọng.
- **Luồng chính:** Hệ thống chuyển tác vụ sang VERIFYING, kiểm tra kích thước, tính SHA-256 của dữ liệu nhận và so sánh với giá trị kỳ vọng. Khi khớp, tệp được hoàn tất tại đích.
- **Ngoại lệ:** Kích thước hoặc SHA-256 không khớp → không báo thành công; dữ liệu không hợp lệ được xử lý theo cơ chế dọn dẹp của transport tương ứng.
- **Hậu điều kiện:** Tệp chỉ được coi là hợp lệ khi vượt qua kiểm tra.

### UC-08 — Cung cấp tập tin cho peer

- **Actor chính:** Peer từ xa.
- **Tiền điều kiện:** Peer cục bộ đang RUNNING và có thư mục chia sẻ.
- **Kích hoạt:** Peer từ xa gửi yêu cầu danh sách, metadata hoặc range dữ liệu qua kết nối TCP.
- **Luồng chính:**
  1. Java nhận và kiểm tra yêu cầu theo giao thức.
  2. Với yêu cầu danh sách, hệ thống trả metadata các tệp được phép chia sẻ.
  3. Với yêu cầu thông tin tệp, hệ thống trả tên, kích thước và SHA-256.
  4. Với yêu cầu range, hệ thống mở tệp hợp lệ và gửi phần dữ liệu được yêu cầu.
  5. Hệ thống ghi nhận sự kiện hoạt động liên quan.
- **Ngoại lệ:** Tệp không tồn tại, tên chứa đường dẫn/chuỗi traversal, symlink hoặc tệp ngoài thư mục chia sẻ → từ chối yêu cầu.
- **Hậu điều kiện:** Chỉ dữ liệu nằm trong phạm vi thư mục chia sẻ mới được cung cấp.

### UC-09 — Theo dõi nhật ký hoạt động

- **Actor chính:** Người dùng cục bộ.
- **Tiền điều kiện:** Ứng dụng đã mở.
- **Kích hoạt:** Người dùng mở **Activity log**.
- **Luồng chính:** Giao diện hiển thị các sự kiện như peer started/stopped, connect/disconnect, hoạt động truyền tệp và lỗi; người dùng có thể xóa lịch sử đang hiển thị.
- **Ngoại lệ:** Khi chưa có sự kiện, giao diện hiển thị trạng thái trống.
- **Hậu điều kiện:** Người dùng xem được hoạt động của phiên Desktop hiện tại.
- **Giới hạn:** Danh sách hoạt động được giữ trong trạng thái giao diện của phiên hiện tại, giới hạn tối đa 100 mục; không phải kho nhật ký lâu dài.

### UC-10 — Tiếp tục tải tập tin LAN bị gián đoạn

- **Actor chính:** Người dùng cục bộ.
- **Actor hỗ trợ:** Peer từ xa (nguồn).
- **Tiền điều kiện:** Có tệp \`.part\` và manifest \`.part.resume\` hợp lệ trong thư mục đích.
- **Kích hoạt:** Người dùng chọn **Scan for partials**, sau đó chọn **Resume download** tại tác vụ phù hợp.
- **Luồng chính:**
  1. Người dùng chọn thư mục đích để quét dữ liệu tải dở.
  2. Java đọc manifest, kiểm tra metadata và hash của từng range đã ghi nhận.
  3. Người dùng kết nối với ít nhất một peer nguồn.
  4. Hệ thống kiểm tra nguồn có cùng kích thước và SHA-256 với manifest.
  5. Chỉ các range thiếu hoặc không hợp lệ được tải lại.
  6. Khi hoàn tất, hệ thống kiểm tra SHA-256 toàn tệp.
- **Ngoại lệ:** Manifest hỏng, tệp tạm bị thiếu hoặc nguồn không còn cung cấp đúng phiên bản tệp → không tiếp tục từ dữ liệu không đáng tin cậy.
- **Hậu điều kiện:** Tác vụ được tiếp tục từ các range hợp lệ và chỉ hoàn tất khi xác minh toàn bộ tệp thành công.

### UC-11 — Tải tập tin từ nhiều peer

- **Actor chính:** Người dùng cục bộ.
- **Actor hỗ trợ:** Nhiều peer từ xa.
- **Tiền điều kiện:** Kết quả tìm kiếm có nhiều peer cung cấp tệp cùng tên; các peer đang kết nối.
- **Kích hoạt:** Người dùng chọn tải tệp có nhiều nguồn ứng viên; giao diện gọi \`transfer.downloadMulti\`.
- **Luồng chính:**
  1. Giao diện gửi danh sách peer ứng viên và tên tệp.
  2. Java yêu cầu metadata từ từng peer.
  3. Hệ thống nhóm các nguồn theo cùng kích thước và SHA-256; không ghép dữ liệu từ những tệp khác nội dung.
  4. Hệ thống chia tệp thành range logic 4 MiB và tổ chức tối đa 8 worker đồng thời.
  5. Các range được tải từ nguồn phù hợp; khi một nguồn lỗi, hệ thống thử peer phù hợp khác.
  6. Hệ thống kiểm tra đủ range và SHA-256 toàn tệp trước khi hoàn tất.
- **Ngoại lệ:** Không có nguồn phù hợp hoặc có nhiều nhóm nội dung khác nhau không thể chọn duy nhất → tác vụ lỗi và yêu cầu người dùng chọn nguồn rõ ràng.
- **Hậu điều kiện:** Tệp chỉ hoàn tất khi tất cả range thuộc cùng phiên bản nội dung và hash toàn tệp hợp lệ.

### UC-12 — Chấp nhận kết nối đến

- **Actor chính:** Peer từ xa.
- **Tiền điều kiện:** Peer cục bộ đang RUNNING và listener đang hoạt động; hai bên đã tin cậy fingerprint chứng chỉ cần thiết.
- **Kích hoạt:** Peer từ xa mở kết nối TCP đến listener.
- **Luồng chính:**
  1. Listener nhận socket mới.
  2. Java thực hiện TLS 1.3 mutual authentication.
  3. Java đọc và kiểm tra handshake của peer từ xa.
  4. Nếu hợp lệ, Java đăng ký kết nối với trạng thái CONNECTED.
  5. Giao diện nhận sự kiện và cập nhật danh sách peer.
- **Ngoại lệ:** TLS/fingerprint không hợp lệ, handshake sai hoặc hết thời gian chờ → đóng socket và không đăng ký peer.
- **Hậu điều kiện:** Peer từ xa được thêm vào danh sách kết nối nếu xác thực thành công.

### UC-13 — Quản lý fingerprint peer tin cậy

- **Actor chính:** Người dùng cục bộ.
- **Tiền điều kiện:** Java backend sẵn sàng.
- **Kích hoạt:** Người dùng mở **Trust & Security**.
- **Luồng chính:**
  1. Hệ thống hiển thị fingerprint chứng chỉ cục bộ và danh sách fingerprint tin cậy.
  2. Người dùng trao đổi fingerprint qua một kênh độc lập đã xác thực.
  3. Người dùng nhập fingerprint của peer và chọn Trust.
  4. Hệ thống kiểm tra định dạng SHA-256 và lưu fingerprint vào trust store.
  5. Khi cần, người dùng chọn Revoke để xóa fingerprint khỏi danh sách tin cậy.
- **Ngoại lệ:** Fingerprint sai định dạng hoặc trùng fingerprint cục bộ → từ chối thao tác.
- **Hậu điều kiện:** Trust store được cập nhật; kết nối TLS tương lai chỉ được chấp nhận với danh tính được tin cậy.

### UC-14 — Tạo lời mời kết nối Internet

- **Actor chính:** Người dùng cục bộ.
- **Actor hỗ trợ:** Signaling Server, Dịch vụ ICE và Peer từ xa.
- **Tiền điều kiện:** Java backend READY; peer ID cục bộ khả dụng; người dùng cấu hình URL HTTPS của Signaling Server.
- **Kích hoạt:** Người dùng chọn **Create invite**.
- **Luồng chính:**
  1. Ứng dụng gửi yêu cầu tạo phòng đến Signaling Server.
  2. Signaling Server tạo phòng hai peer có thời hạn và trả room ID, room secret, hạn dùng.
  3. Ứng dụng tạo lời mời chứa thông tin phòng và bí mật bearer.
  4. WebRTC bắt đầu quá trình tạo offer và thu thập thông tin kết nối.
  5. Giao diện hiển thị mã mời để người dùng gửi riêng cho peer nhận.
- **Ngoại lệ:** URL không hợp lệ, Signaling Server không truy cập được hoặc phòng không tạo được → phiên chuyển FAILED và hiển thị lỗi.
- **Hậu điều kiện:** Có lời mời tạm thời để peer khác tham gia.
- **Lưu ý bảo mật:** Mã mời chứa room secret, phải được chia sẻ qua kênh riêng và không đăng công khai.

### UC-15 — Tham gia lời mời kết nối Internet

- **Actor chính:** Người dùng cục bộ.
- **Actor hỗ trợ:** Signaling Server, Dịch vụ ICE và Peer từ xa.
- **Tiền điều kiện:** Người dùng có mã mời còn hạn; peer ID cục bộ khả dụng.
- **Kích hoạt:** Người dùng dán mã mời và chọn **Join device**.
- **Luồng chính:**
  1. Ứng dụng kiểm tra cấu trúc, phiên bản và hạn dùng của mã mời.
  2. Ứng dụng gửi yêu cầu tham gia phòng đến Signaling Server.
  3. Hai peer trao đổi thông tin SDP và ICE thông qua signaling.
  4. WebRTC sử dụng ICE để thử các đường kết nối; STUN/TURN được dùng tùy cấu hình và khả năng mạng.
  5. Khi DataChannel mở, ứng dụng chuyển sang bước xác minh mã an toàn.
- **Ngoại lệ:** Mã mời sai/hết hạn, phòng không còn tồn tại hoặc ICE không thiết lập được kết nối → phiên chuyển FAILED và hiển thị lỗi.
- **Hậu điều kiện:** Hai peer có phiên WebRTC đang chờ xác minh danh tính.
- **Giới hạn kiểm thử:** Khả năng kết nối thực tế phụ thuộc signaling HTTPS và TURN có thể truy cập; chưa mặc nhiên bảo đảm hoạt động qua mọi NAT/Firewall.

### UC-16 — Xác nhận mã an toàn của peer Internet

- **Actor chính:** Người dùng cục bộ.
- **Actor hỗ trợ:** Peer từ xa.
- **Tiền điều kiện:** WebRTC DataChannel đã mở và hai fingerprint DTLS có thể đọc được.
- **Kích hoạt:** Ứng dụng hiển thị mã an toàn.
- **Luồng chính:**
  1. Hai phía tính mã xác nhận 8 chữ số từ hai DTLS SHA-256 fingerprints.
  2. Người dùng đối chiếu mã với người ở thiết bị còn lại qua một kênh độc lập.
  3. Người dùng chọn **Confirm code** nếu hai mã trùng nhau.
  4. Ứng dụng gửi xác nhận cho peer còn lại.
  5. Chỉ khi cả hai phía đã xác nhận cùng mã, phiên mới ở trạng thái secure và cho phép thao tác tệp.
- **Ngoại lệ:** Mã không trùng hoặc một bên không xác nhận → không mở quyền truyền tệp.
- **Hậu điều kiện:** Phiên được xác minh ở cả hai đầu hoặc vẫn bị khóa chức năng truyền tệp.

### UC-17 — Gửi tập tin qua Internet

- **Actor chính:** Người dùng cục bộ (Sender).
- **Actor hỗ trợ:** Peer từ xa (Receiver).
- **Tiền điều kiện:** Phiên Internet ở trạng thái secure; đã chọn thư mục chia sẻ; tệp nằm trong thư mục đó.
- **Kích hoạt:** Người gửi chọn **Send file** tại một tệp.
- **Luồng chính:**
  1. Electron Main mở tệp hợp lệ trong thư mục chia sẻ, kiểm tra đường dẫn, kích thước và tính SHA-256.
  2. Ứng dụng gửi file offer gồm transfer ID, tên, kích thước và SHA-256.
  3. Peer nhận hiển thị lời mời nhận tệp.
  4. Sau khi peer nhận chấp nhận, sender truyền các khối dữ liệu qua WebRTC DataChannel.
  5. Hệ thống kiểm soát cửa sổ ACK tối đa 4 khối, mỗi khối tối đa 48 KiB.
  6. Khi nhận xác nhận hoàn tất, giao diện cập nhật trạng thái.
- **Ngoại lệ:** Peer từ chối, phiên mất kết nối hoặc người dùng hủy → dừng gửi, đóng file handle và cập nhật trạng thái tương ứng.
- **Hậu điều kiện:** Tệp được gửi qua DataChannel; sender chỉ coi tác vụ hoàn tất khi nhận thông điệp hoàn tất hợp lệ.

### UC-18 — Tiếp nhận tập tin qua Internet

- **Actor chính:** Người dùng cục bộ (Receiver).
- **Actor hỗ trợ:** Peer từ xa (Sender).
- **Tiền điều kiện:** Phiên Internet ở trạng thái secure; sender đã gửi file offer.
- **Kích hoạt:** Ứng dụng hiển thị incoming file offer.
- **Luồng chính:**
  1. Giao diện hiển thị tên, kích thước và SHA-256 của tệp.
  2. Người dùng chọn Accept hoặc Decline.
  3. Nếu Accept, Electron mở hộp thoại chọn vị trí lưu và tạo vùng nhận tạm trong thư mục dữ liệu riêng của ứng dụng.
  4. Dữ liệu được nhận theo offset qua DataChannel và ghi vào tệp tạm.
  5. Các checkpoint được đồng bộ xuống đĩa và manifest được cập nhật.
  6. Khi nhận đủ dữ liệu, hệ thống kiểm tra kích thước và SHA-256 qua UC-07a.
  7. Nếu hợp lệ, tệp được công bố tại vị trí đích mà không ghi đè tệp đã có.
- **Luồng thay thế:**
  - A1: Người dùng chọn Decline → gửi phản hồi từ chối, không tạo tệp đích.
  - A2: Có dữ liệu tạm cùng transfer identity hợp lệ → giao diện hiển thị số byte có thể tiếp tục; khi Accept, hệ thống gửi offset đã lưu và tiếp tục từ checkpoint.
  - A3: Hủy truyền đang hoạt động → chuyển sang UC-19; thao tác hủy nhận chủ động xóa dữ liệu tạm của tác vụ.
  - A4: Phiên đóng hoặc ứng dụng tắt → dữ liệu đã checkpoint được giữ lại để lần nhận sau có thể tiếp tục.
- **Ngoại lệ:** Manifest hoặc dữ liệu tạm không hợp lệ, sai offset, sai thứ tự chunk hoặc SHA-256 cuối cùng không khớp → không công bố tệp.
- **Hậu điều kiện:** Tệp hoàn chỉnh chỉ được xuất hiện tại vị trí đích sau khi kiểm tra thành công.

### UC-19 — Hủy tác vụ truyền tập tin

- **Actor chính:** Người dùng cục bộ.
- **Actor hỗ trợ:** Peer từ xa (đối với truyền Internet).
- **Tiền điều kiện:** Có tác vụ truyền đang hoạt động.
- **Kích hoạt:** Người dùng chọn **Cancel** tại tác vụ.
- **Luồng chính:**
  1. Người dùng yêu cầu hủy.
  2. Với tải LAN, Java dừng các worker, cập nhật CANCELLED và xóa tệp tạm/manifest của tác vụ bị hủy.
  3. Với truyền Internet, ứng dụng gửi thông điệp hủy cho peer và đóng file handle liên quan.
  4. Với phía nhận Internet, thao tác hủy chủ động xóa vùng dữ liệu tạm và manifest của transfer.
  5. Giao diện bỏ tiến trình đang hiển thị và cập nhật trạng thái.
- **Ngoại lệ:** Tác vụ đã ở trạng thái kết thúc → không thực hiện hủy lần nữa.
- **Hậu điều kiện:** Tác vụ bị dừng; dữ liệu tạm được xử lý theo chính sách hủy của transport.

### UC-20 — Đóng phiên kết nối Internet

- **Actor chính:** Người dùng cục bộ.
- **Actor hỗ trợ:** Peer từ xa.
- **Tiền điều kiện:** Đang có phiên Internet.
- **Kích hoạt:** Người dùng chọn **Close session**.
- **Luồng chính:**
  1. Ứng dụng ngừng trao đổi signaling và đóng DataChannel/RTCPeerConnection.
  2. File handle gửi đang mở được đóng.
  3. Nếu đang nhận tệp, ứng dụng suspend và giữ lại dữ liệu đã checkpoint thay vì xử lý như thao tác Cancel.
  4. Trạng thái phiên chuyển CLOSED; mã an toàn và thông tin lời mời trên giao diện được xóa.
- **Ngoại lệ:** Nếu kết nối đã đóng trước đó, ứng dụng đồng bộ trạng thái CLOSED và dọn dẹp tài nguyên còn lại.
- **Hậu điều kiện:** Phiên Internet kết thúc; dữ liệu nhận đã checkpoint vẫn có thể được khôi phục trong lần mời/nhận tiếp theo nếu metadata tệp tương ứng.

## 6. Quy tắc mô hình hóa và giới hạn

1. Người dùng cục bộ và Peer từ xa là hai Actor chính. Cùng một người sử dụng ứng dụng có thể vừa gửi vừa nhận tệp; Sender/Receiver là vai trò theo từng phiên, không phải loại tài khoản.
2. Không mô hình hóa React, Electron Main/Preload, Java Core, Socket hoặc File System thành Actor.
3. Không mô hình hóa database/ERD vì hệ thống không sử dụng cơ sở dữ liệu quan hệ cho nghiệp vụ chia sẻ tệp.
4. Tìm kiếm LAN chỉ truy vấn các peer đã kết nối; hệ thống không tự động dò tìm peer trong toàn mạng.
5. Signaling Server chỉ xử lý metadata và thông tin thiết lập phiên. Nội dung tệp truyền qua DataChannel trực tiếp hoặc qua TURN relay khi ICE chọn đường relay.
6. Resume LAN do người dùng quét và chọn tác vụ tải dở. Resume Internet dựa trên transfer identity và checkpoint của phía nhận; nó khác với thao tác Cancel. Đóng phiên giữ checkpoint, còn Cancel chủ động xóa dữ liệu tạm của tác vụ đang nhận.
7. SRS v0.2 mô tả chủ yếu phạm vi P2P/TCP ban đầu. Các UC-13 đến UC-20 được bổ sung từ các chức năng hiện có trong mã nguồn Desktop; cần cập nhật SRS tương ứng nếu tiếp tục duy trì truy vết FR/NFR đầy đủ.
8. Mã nguồn WebRTC phía Desktop đã có, nhưng kiểm thử end-to-end trên các mạng độc lập với Signaling HTTPS/TURN công khai vẫn cần được thực hiện trước khi tuyên bố khả năng kết nối Internet đã được kiểm chứng trong môi trường triển khai thực tế.
