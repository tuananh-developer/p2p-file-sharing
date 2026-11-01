# Use Case Analysis — P2P File Sharing

| Thuộc tính | Giá trị |
|---|---|
| Tài liệu | Phân tích Use Case |
| Dự án | Xây dựng ứng dụng chia sẻ tập tin ngang hàng (P2P File Sharing) |
| Cơ sở | `docs/SRS.md`, phiên bản 0.1 — Draft |
| Phiên bản | 0.1 — Draft |
| Trạng thái | Bản phân tích ban đầu, cần rà soát trước khi chốt |

## 1. Mục đích

Tài liệu xác định các Actor, ranh giới hệ thống, danh sách Use Case và các luồng tương tác chính. Đây là cơ sở để vẽ Use Case Diagram, đặc tả chi tiết từng Use Case, sau đó tiếp tục phân tích đối tượng theo OOAD.

Tài liệu này không mô tả class hay phương thức Java cụ thể; các nội dung đó thuộc giai đoạn Object-Oriented Analysis/Design.

## 2. Ranh giới hệ thống

**Tên hệ thống:** P2P File Sharing Desktop Application.

Ranh giới hệ thống bao gồm React UI, Electron Main/Preload và Java P2P Core của **một instance ứng dụng**. Các thành phần này phối hợp để cung cấp chức năng cho người dùng.

Một instance khác của ứng dụng được xem là **Peer từ xa**, nằm bên ngoài ranh giới của instance hiện tại. Tập tin phải được truyền trực tiếp giữa Java P2P Core của hai peer qua TCP; nội dung tập tin không đi qua React UI hoặc một máy chủ trung tâm.

### Không xem các thành phần sau là Actor

- React UI, Electron Main/Preload và Java P2P Core là các phần bên trong hệ thống.
- `Socket`, hệ điều hành và hệ thống tệp là công nghệ/tài nguyên mà hệ thống sử dụng, không phải Actor nghiệp vụ chính trong Use Case Diagram mức tổng quan.

## 3. Actor

### A-01 — Người dùng cục bộ (Primary Actor)

**Mô tả:** Người dùng đang thao tác trên ứng dụng desktop của peer hiện tại.

**Mục tiêu:** Khởi động/dừng peer, kết nối peer khác, chọn thư mục chia sẻ, tìm và tải tập tin, theo dõi trạng thái và hoạt động.

**Use Case tham gia:** UC-01 đến UC-07, UC-09 và UC-10/UC-11 ở giai đoạn nâng cao.

### A-02 — Peer từ xa (External Supporting/Initiating Actor)

**Mô tả:** Một instance ứng dụng P2P khác đang chạy trên cùng máy hoặc máy khác trong mạng có thể truy cập được.

**Vai trò:** Có thể là peer mà người dùng hiện tại muốn kết nối tới, peer cung cấp tập tin cho người dùng hiện tại, hoặc peer gửi yêu cầu lấy danh sách/tải tập tin từ peer hiện tại.

**Use Case tham gia:** UC-03, UC-04, UC-06, UC-07, UC-08 và UC-11, tùy vai trò của peer trong từng luồng.

> Trong UML, A-02 là một Actor vì nó nằm ngoài ranh giới của instance đang mô hình hóa. Nó vẫn là cùng loại phần mềm, không phải một máy chủ trung tâm.

## 4. Danh sách Use Case

| Mã | Tên Use Case | Actor chính | Actor hỗ trợ | Yêu cầu liên quan | Mức ưu tiên |
|---|---|---|---|---|---|
| UC-01 | Khởi chạy peer | Người dùng cục bộ | — | FR-01, FR-02, FR-20 | MVP |
| UC-02 | Dừng peer | Người dùng cục bộ | — | FR-03, FR-25 | MVP |
| UC-03 | Kết nối peer | Người dùng cục bộ | Peer từ xa | FR-04, FR-21–FR-23 | MVP |
| UC-04 | Ngắt kết nối peer | Người dùng cục bộ | Peer từ xa | FR-06, FR-07 | MVP |
| UC-05 | Chọn thư mục chia sẻ | Người dùng cục bộ | — | FR-08, FR-14 | Bản hoàn chỉnh |
| UC-06 | Xem/tìm kiếm tập tin | Người dùng cục bộ | Peer từ xa | FR-09–FR-12 | Bản hoàn chỉnh |
| UC-07 | Tải tập tin | Người dùng cục bộ | Peer từ xa (nguồn) | FR-13, FR-15–FR-19 | Bản hoàn chỉnh |
| UC-08 | Cung cấp tập tin cho peer | Peer từ xa | — | FR-14, FR-18 | Bản hoàn chỉnh |
| UC-09 | Theo dõi nhật ký hoạt động | Người dùng cục bộ | — | FR-29 | Bản hoàn chỉnh |
| UC-10 | Tiếp tục tải tập tin bị gián đoạn | Người dùng cục bộ | Peer từ xa | FR-28 | Nâng cao |
| UC-11 | Tải tập tin từ nhiều peer | Người dùng cục bộ | Các peer từ xa | FR-26, FR-27 | Nâng cao |

### Lưu ý về truy vết SRS

SRS v0.1 đang gộp FR-26–FR-28 vào UC-10 “Tiếp tục tải tập tin”. Tại bước phân tích, nên tách rõ **tiếp tục tải sau gián đoạn** (FR-28) và **chia phần/tải nhiều nguồn** (FR-26–FR-27), vì đây là các mục tiêu và luồng xử lý khác nhau. Đề xuất trong tài liệu này là một bước làm rõ; sau khi được chấp nhận, cập nhật lại bảng Use Case và ma trận truy vết trong SRS để tránh lệch mã.

## 5. Quan hệ giữa Use Case

1. **UC-06 — Xem/tìm kiếm tập tin** bao gồm việc yêu cầu danh sách tập tin từ các peer đã kết nối khi người dùng làm mới danh sách. Việc tìm kiếm chỉ giới hạn trong dữ liệu nhận được từ những peer có thể truy vấn; không phải tìm kiếm toàn mạng Internet.
2. **UC-07 — Tải tập tin** phải bao gồm kiểm tra tính toàn vẹn khi đã nhận đủ dữ liệu. Nếu SHA-256 không khớp, tác vụ không được báo thành công.
3. **UC-08 — Cung cấp tập tin cho peer** là luồng tương tác từ góc nhìn của peer hiện tại khi peer từ xa yêu cầu danh sách hoặc nội dung tập tin. Hệ thống phải xác minh tập tin nằm trong phạm vi chia sẻ trước khi cung cấp.
4. **UC-10 — Tiếp tục tải** và **UC-11 — Tải từ nhiều peer** là chức năng nâng cao, không phải điều kiện cần để nghiệm thu MVP.
5. Không dùng `<<include>>` chỉ để thể hiện trình tự thời gian giữa các Use Case. Ví dụ, người dùng thường tìm file rồi mới tải file, nhưng đây là luồng công việc chứ không tự động có nghĩa UC-07 `include` UC-06.

## 6. Đặc tả Use Case

### UC-01 — Khởi chạy peer

- **Mục tiêu:** Cho phép peer hiện tại bắt đầu lắng nghe kết nối TCP trên cổng được chọn.
- **Actor chính:** Người dùng cục bộ.
- **Tiền điều kiện:** Ứng dụng desktop đã mở; Java backend có thể khởi chạy.
- **Kích hoạt:** Người dùng nhập cổng và chọn “Start Peer”.
- **Luồng chính:**
  1. Người dùng nhập cổng lắng nghe hợp lệ.
  2. Giao diện gửi lệnh qua Electron bridge đến Java backend.
  3. Java kiểm tra dữ liệu đầu vào và thử bind socket trên cổng được yêu cầu.
  4. Java phản hồi trạng thái khởi chạy thành công.
  5. Giao diện hiển thị peer ở trạng thái đang chạy cùng cổng lắng nghe.
- **Luồng thay thế/ngoại lệ:**
  - A1: Cổng không hợp lệ → thông báo lỗi và yêu cầu nhập lại.
  - A2: Cổng đang bị chiếm dụng hoặc không đủ quyền → peer không được đánh dấu đang chạy; hiển thị lỗi.
  - A3: Java backend khởi động thất bại → hiển thị lỗi backend, không giả lập thành công.
- **Hậu điều kiện thành công:** Peer đang lắng nghe trên cổng đã chọn.
- **Hậu điều kiện thất bại:** Peer không ở trạng thái đang chạy và tài nguyên đã mở một phần được giải phóng.
- **Yêu cầu liên quan:** FR-01, FR-02, FR-20.

### UC-02 — Dừng peer

- **Mục tiêu:** Dừng listener và đóng tài nguyên mạng do peer hiện tại quản lý.
- **Actor chính:** Người dùng cục bộ.
- **Tiền điều kiện:** Peer đang chạy.
- **Kích hoạt:** Người dùng chọn “Stop Peer”.
- **Luồng chính:**
  1. Người dùng yêu cầu dừng peer.
  2. Ứng dụng gửi lệnh dừng tới Java backend.
  3. Java ngừng nhận kết nối mới, đóng listener và dọn dẹp kết nối/tác vụ theo chính sách dừng đã thiết kế.
  4. Java trả về kết quả; giao diện cập nhật trạng thái đã dừng.
- **Ngoại lệ:** Nếu có lỗi trong quá trình dọn dẹp, hệ thống ghi log và hiển thị trạng thái phù hợp; không báo dừng hoàn toàn khi tài nguyên vẫn còn hoạt động.
- **Hậu điều kiện:** Listener được đóng và cổng có thể được giải phóng trong điều kiện bình thường.
- **Yêu cầu liên quan:** FR-03, FR-25, NFR-10.

### UC-03 — Kết nối peer

- **Mục tiêu:** Thiết lập kết nối TCP với một peer từ xa.
- **Actor chính:** Người dùng cục bộ.
- **Actor hỗ trợ:** Peer từ xa.
- **Tiền điều kiện:** Peer cục bộ đang chạy; người dùng biết địa chỉ và cổng đích.
- **Kích hoạt:** Người dùng nhập IP/hostname, cổng và chọn “Connect”.
- **Luồng chính:**
  1. Người dùng nhập địa chỉ và cổng của peer đích.
  2. Giao diện gửi yêu cầu kết nối qua Electron bridge.
  3. Java kiểm tra dữ liệu, thực hiện kết nối với timeout hữu hạn.
  4. Hai peer trao đổi thông điệp điều khiển/handshake theo giao thức đã định nghĩa.
  5. Nếu handshake thành công, Java cập nhật danh sách peer và phát sự kiện trạng thái.
  6. Giao diện hiển thị peer đã kết nối.
- **Luồng thay thế/ngoại lệ:**
  - A1: Địa chỉ/cổng sai hoặc peer không chạy → hiển thị lỗi sau timeout.
  - A2: Peer từ xa từ chối hoặc gửi thông điệp không hợp lệ → đóng kết nối và báo lỗi.
  - A3: Đã tồn tại kết nối tương đương → không tạo bản ghi trùng; thông báo trạng thái phù hợp.
- **Hậu điều kiện thành công:** Kết nối được quản lý trong danh sách peer.
- **Yêu cầu liên quan:** FR-04, FR-05, FR-21–FR-23, NFR-05, NFR-11.

### UC-04 — Ngắt kết nối peer

- **Mục tiêu:** Đóng kết nối tới peer đã chọn.
- **Actor chính:** Người dùng cục bộ.
- **Actor hỗ trợ:** Peer từ xa.
- **Tiền điều kiện:** Peer mục tiêu đang được quản lý là đã kết nối.
- **Kích hoạt:** Người dùng chọn peer và chọn “Disconnect”.
- **Luồng chính:**
  1. Người dùng chọn peer cần ngắt kết nối.
  2. Java đóng socket/kết nối tương ứng và dọn dẹp tác vụ phụ thuộc theo chính sách.
  3. Java phát sự kiện trạng thái mới.
  4. Giao diện cập nhật peer thành disconnected.
- **Luồng thay thế:** Nếu peer đã mất kết nối trước đó, hệ thống đồng bộ trạng thái thành disconnected mà không làm ứng dụng lỗi.
- **Hậu điều kiện:** Kết nối không còn được xem là hoạt động.
- **Yêu cầu liên quan:** FR-06, FR-07.

### UC-05 — Chọn thư mục chia sẻ

- **Mục tiêu:** Cho phép người dùng chọn nguồn tập tin mà peer hiện tại được phép cung cấp.
- **Actor chính:** Người dùng cục bộ.
- **Tiền điều kiện:** Ứng dụng đã mở; Java backend sẵn sàng để nhận cấu hình.
- **Kích hoạt:** Người dùng mở chức năng chọn thư mục.
- **Luồng chính:**
  1. Electron mở hộp thoại chọn thư mục của hệ điều hành.
  2. Người dùng chọn thư mục.
  3. Ứng dụng kiểm tra thư mục tồn tại và có thể truy cập.
  4. Đường dẫn được gửi đến Java backend qua command đã định nghĩa.
  5. Giao diện hiển thị thư mục chia sẻ hiện tại.
- **Ngoại lệ:** Người dùng hủy hộp thoại, đường dẫn không hợp lệ hoặc không có quyền đọc → giữ cấu hình trước đó và hiển thị thông báo phù hợp.
- **Hậu điều kiện:** Hệ thống lưu cấu hình thư mục chia sẻ được chấp nhận.
- **Điểm cần chốt:** Phạm vi file có bao gồm thư mục con hay chỉ file cấp đầu tiên; yêu cầu này đang là vấn đề mở trong SRS.
- **Yêu cầu liên quan:** FR-08, FR-14, NFR-12.

### UC-06 — Xem/tìm kiếm tập tin

- **Mục tiêu:** Xem danh sách tập tin được chia sẻ từ các peer đang kết nối và tìm file theo tên.
- **Actor chính:** Người dùng cục bộ.
- **Actor hỗ trợ:** Peer từ xa.
- **Tiền điều kiện:** Ứng dụng đã mở; để truy vấn từ xa, có ít nhất một peer đang kết nối.
- **Kích hoạt:** Người dùng chọn làm mới danh sách hoặc nhập từ khóa tìm kiếm.
- **Luồng chính:**
  1. Người dùng yêu cầu làm mới danh sách tập tin từ một hoặc nhiều peer, hoặc nhập từ khóa tìm kiếm.
  2. Java gửi yêu cầu danh sách đến peer được chọn/đang kết nối theo phạm vi đã thiết kế.
  3. Peer từ xa trả về metadata được phép chia sẻ, không gửi nội dung file ở bước này.
  4. Java tổng hợp kết quả và gắn mỗi file với peer cung cấp.
  5. Giao diện hiển thị kết quả; khi tìm kiếm, hệ thống lọc theo tên và hiển thị peer nguồn.
- **Luồng thay thế/ngoại lệ:**
  - A1: Không có peer đang kết nối → thông báo trạng thái rỗng, không treo giao diện.
  - A2: Một peer không phản hồi → hiển thị lỗi cho nguồn đó nhưng vẫn giữ kết quả từ peer khác.
  - A3: Không có kết quả phù hợp → hiển thị empty state.
- **Hậu điều kiện:** Người dùng có danh sách kết quả gắn với nguồn cung cấp.
- **Giới hạn:** Chỉ tìm trong danh sách do các peer có thể truy vấn cung cấp; không tìm kiếm toàn mạng.
- **Yêu cầu liên quan:** FR-09–FR-12, BR-04.

### UC-07 — Tải tập tin

- **Mục tiêu:** Tải một tập tin từ peer nguồn về vị trí do người dùng chọn.
- **Actor chính:** Người dùng cục bộ.
- **Actor hỗ trợ:** Peer từ xa (peer nguồn).
- **Tiền điều kiện:** Peer cục bộ đang chạy; có kết quả tập tin từ một peer nguồn đang có thể kết nối.
- **Kích hoạt:** Người dùng chọn tập tin, chọn nơi lưu và xác nhận tải.
- **Luồng chính:**
  1. Người dùng chọn tập tin và peer nguồn.
  2. Người dùng chọn thư mục đích hoặc chấp nhận thư mục lưu đã cấu hình.
  3. Hệ thống kiểm tra đường dẫn đích và xử lý xung đột tên theo chính sách an toàn.
  4. Java gửi yêu cầu tới peer nguồn và nhận metadata cần thiết cho việc truyền.
  5. Peer nguồn xác minh yêu cầu, phạm vi chia sẻ và gửi dữ liệu qua TCP trực tiếp.
  6. Java ghi dữ liệu xuống ổ đĩa ở tác vụ nền và phát tiến trình cho giao diện.
  7. Khi nhận đủ dữ liệu, hệ thống kiểm tra kích thước/độ đầy đủ và SHA-256 theo giao thức.
  8. Nếu kiểm tra đạt, giao diện hiển thị hoàn tất; nếu không đạt, hiển thị lỗi và không đánh dấu file là hợp lệ.
- **Luồng thay thế/ngoại lệ:**
  - A1: File không còn tồn tại trên nguồn → báo lỗi.
  - A2: Peer nguồn ngắt kết nối → đánh dấu thất bại hoặc trạng thái có thể tiếp tục nếu phần resume đã được triển khai.
  - A3: Không có quyền ghi/đích trùng → yêu cầu người dùng chọn hành động khác; không âm thầm ghi đè.
  - A4: SHA-256 không khớp → báo file không hợp lệ và giữ/loại file tạm theo chính sách đã định nghĩa.
  - A5: Người dùng hủy tải → dừng tác vụ và giải phóng tài nguyên.
- **Hậu điều kiện thành công:** File được lưu tại đích và vượt qua kiểm tra toàn vẹn.
- **Yêu cầu liên quan:** FR-13, FR-15–FR-19, BR-05–BR-07, NFR-04, NFR-08, NFR-12.

### UC-08 — Cung cấp tập tin cho peer

- **Mục tiêu:** Cho phép một peer từ xa xem metadata hoặc tải một file thuộc phạm vi được chia sẻ của peer hiện tại.
- **Actor chính:** Peer từ xa.
- **Tiền điều kiện:** Peer hiện tại đang chạy và có thư mục chia sẻ được cấu hình.
- **Kích hoạt:** Peer từ xa gửi yêu cầu danh sách hoặc yêu cầu tải một file.
- **Luồng chính:**
  1. Java nhận và phân tích yêu cầu từ kết nối TCP.
  2. Java kiểm tra cấu trúc yêu cầu và loại thao tác.
  3. Với yêu cầu danh sách, hệ thống chỉ trả metadata được phép chia sẻ.
  4. Với yêu cầu tải, hệ thống xác định file theo định danh/đường dẫn tương đối an toàn.
  5. Hệ thống xác minh file nằm trong phạm vi chia sẻ và có thể đọc.
  6. Java truyền dữ liệu trực tiếp tới peer yêu cầu và ghi nhận trạng thái/log.
- **Luồng thay thế/ngoại lệ:** File không tồn tại, đường dẫn vượt phạm vi chia sẻ, không đủ quyền đọc, yêu cầu sai định dạng hoặc kết nối bị đóng → từ chối yêu cầu và trả lỗi giao thức thích hợp.
- **Hậu điều kiện:** Yêu cầu hợp lệ được phục vụ; yêu cầu không hợp lệ không được phép truy cập file ngoài phạm vi.
- **Yêu cầu liên quan:** FR-14, FR-18, BR-03, BR-05, NFR-12, NFR-13.

### UC-09 — Theo dõi nhật ký hoạt động

- **Mục tiêu:** Giúp người dùng xem các sự kiện quan trọng trong ứng dụng.
- **Actor chính:** Người dùng cục bộ.
- **Tiền điều kiện:** Ứng dụng đã mở.
- **Kích hoạt:** Người dùng mở khu vực Activity Log hoặc có sự kiện mới được ghi nhận.
- **Luồng chính:**
  1. Hệ thống ghi nhận các sự kiện được phép như peer started/stopped, connect/disconnect, bắt đầu/hủy tải và lỗi.
  2. Giao diện nhận sự kiện và hiển thị theo thứ tự thời gian.
  3. Người dùng xem log để hiểu trạng thái hoặc chẩn đoán lỗi.
- **Ngoại lệ:** Nếu log quá dài, ứng dụng có thể giới hạn số bản ghi hiển thị; không ghi nội dung file hoặc dữ liệu nhạy cảm vào log.
- **Hậu điều kiện:** Người dùng có thông tin trạng thái hoạt động cần thiết.
- **Yêu cầu liên quan:** FR-29, NFR-09, NFR-20.

### UC-10 — Tiếp tục tải tập tin bị gián đoạn (Nâng cao)

- **Mục tiêu:** Tiếp tục tải các phần còn thiếu mà không tải lại toàn bộ file nếu đã lưu được metadata và dữ liệu tạm hợp lệ.
- **Actor chính:** Người dùng cục bộ.
- **Actor hỗ trợ:** Peer từ xa.
- **Tiền điều kiện:** Tác vụ trước đó bị gián đoạn; metadata tiến trình và các phần đã tải còn hợp lệ; có nguồn file tương thích.
- **Kích hoạt:** Người dùng chọn tiếp tục tác vụ đang dang dở.
- **Luồng chính:** Hệ thống đọc metadata, xác minh nguồn và trạng thái các phần đã nhận, yêu cầu những phần còn thiếu, ghép dữ liệu và kiểm tra SHA-256 sau khi hoàn tất.
- **Ngoại lệ:** Metadata hỏng, nguồn không còn cung cấp file hoặc file nguồn đã thay đổi → yêu cầu tải lại hoặc báo không thể tiếp tục.
- **Hậu điều kiện:** Tác vụ được tiếp tục và hoàn tất hợp lệ, hoặc người dùng nhận thông báo rõ ràng rằng không thể tiếp tục.
- **Yêu cầu liên quan:** FR-28.

### UC-11 — Tải tập tin từ nhiều peer (Nâng cao)

- **Mục tiêu:** Tải các phần của cùng một tập tin từ nhiều peer nguồn để tận dụng nhiều nguồn cung cấp.
- **Actor chính:** Người dùng cục bộ.
- **Actor hỗ trợ:** Các peer từ xa có cùng nội dung file.
- **Tiền điều kiện:** Giao thức hỗ trợ chia phần; file có thể được nhận diện nhất quán giữa các nguồn; ít nhất hai nguồn tương thích cùng cung cấp file.
- **Kích hoạt:** Người dùng chọn chế độ tải nhiều nguồn hoặc hệ thống chọn chế độ này theo thiết kế.
- **Luồng chính:**
  1. Hệ thống xác định các nguồn cung cấp cùng một file bằng metadata/định danh đã thống nhất.
  2. File được chia thành các phần với offset và length xác định.
  3. Các phần được phân phối tới các nguồn phù hợp và truyền song song trong giới hạn cấu hình.
  4. Java ghi các phần đúng vị trí và theo dõi trạng thái từng phần.
  5. Khi đủ các phần, hệ thống ghép/hoàn thiện file và kiểm tra SHA-256.
- **Ngoại lệ:** Một nguồn biến mất hoặc phần nhận bị lỗi → đánh dấu phần chưa hoàn tất, thử nguồn khác nếu còn; nếu không thể hoàn thành, báo tác vụ chưa hoàn tất.
- **Hậu điều kiện:** File hoàn tất chỉ khi đủ phần và kiểm tra toàn vẹn đạt.
- **Yêu cầu liên quan:** FR-26, FR-27, FR-17.

## 7. Đề xuất kiểm tra tính nhất quán trước khi chốt

1. Cập nhật SRS để UC-10 chỉ liên kết FR-28 và bổ sung UC-11 liên kết FR-26–FR-27.
2. Xác định rõ `FR-08` chọn thư mục chia sẻ có lưu cấu hình qua lần khởi động hay chỉ có hiệu lực trong phiên; hiện SRS chưa chốt.
3. Chốt việc chia sẻ thư mục con hay chỉ thư mục cấp đầu.
4. Chốt cách định danh file trong giao thức (không nên tin đường dẫn tuyệt đối do peer từ xa gửi).
5. Chốt hành vi dừng peer với các kết nối và tác vụ tải đang hoạt động.
6. Giữ UC-10 và UC-11 ngoài MVP để không làm tăng phạm vi kiểm thử ban đầu.

## 8. Bước OOAD tiếp theo

Sau khi rà soát Actor và Use Case, bước kế tiếp là:

1. Chốt Use Case Diagram.
2. Đặc tả chi tiết các Use Case trong MVP (đặc biệt UC-01 đến UC-04).
3. Từ các luồng và dữ liệu trao đổi, xây dựng Domain Model/Conceptual Class Diagram.
4. Sau đó mới chuyển sang thiết kế class, sequence diagram và kiến trúc triển khai chi tiết.
