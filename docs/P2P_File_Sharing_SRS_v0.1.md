# SOFTWARE REQUIREMENTS SPECIFICATION (SRS)

## Xây dựng ứng dụng chia sẻ tập tin ngang hàng (Peer-to-Peer File Sharing) bằng Java

| Thông tin | Giá trị |
|---|---|
| Mã tài liệu | SRS-P2P-001 |
| Phiên bản | 0.1 — Draft |
| Trạng thái | Bản nháp yêu cầu, chưa được nghiệm thu |
| Ngày cập nhật | 15/10/2026 |
| Nền tảng mục tiêu | Fedora Linux |
| Công nghệ chính | Java, Maven, React, TypeScript, Electron, TCP/IP, JUnit 5 |
| Người thực hiện | Một sinh viên |

> **Quy ước trạng thái:** Các yêu cầu trong tài liệu là yêu cầu mục tiêu, chưa có nghĩa là đã được triển khai hoặc kiểm thử. Những chức năng được đánh dấu **MVP** thuộc phạm vi phiên bản nền tảng đầu tiên; những chức năng **Nâng cao** sẽ được thực hiện sau khi các chức năng cốt lõi hoạt động ổn định.

---

# 1. Giới thiệu

## 1.1. Mục đích tài liệu

Tài liệu này xác định các yêu cầu chức năng, yêu cầu phi chức năng, phạm vi, ràng buộc và tiêu chí nghiệm thu của ứng dụng chia sẻ tập tin ngang hàng (P2P File Sharing). Tài liệu là cơ sở cho các bước tiếp theo của quy trình OOAD, bao gồm xác định Actor, xây dựng Use Case Diagram, đặc tả Use Case, phân tích đối tượng, thiết kế UML, hiện thực và kiểm thử.

## 1.2. Phạm vi sản phẩm

Ứng dụng là phần mềm desktop cho phép người dùng khởi chạy một peer, kết nối trực tiếp đến các peer khác, tìm kiếm tập tin được chia sẻ và truyền tập tin qua mạng. Mỗi instance ứng dụng có một Java P2P Core riêng; một peer có thể vừa chủ động kết nối để tải tập tin, vừa lắng nghe yêu cầu đến để cung cấp tập tin cho peer khác.

Giao diện desktop được xây dựng bằng React và Electron. Java chịu trách nhiệm xử lý kết nối mạng P2P và nghiệp vụ tập tin. Nội dung tập tin được truyền trực tiếp giữa các peer qua TCP, không đi qua giao diện React hoặc một máy chủ trung tâm.

Phiên bản đầu cho phép người dùng nhập thủ công địa chỉ IP và cổng của peer cần kết nối. Hệ thống không yêu cầu máy chủ trung tâm, cơ sở dữ liệu hay dịch vụ cloud để thực hiện các chức năng cốt lõi.

## 1.3. Đối tượng đọc tài liệu

- Người thực hiện đồ án: dùng tài liệu để lập kế hoạch, thiết kế và kiểm thử.
- Giảng viên hướng dẫn/giảng viên môn học: xem xét phạm vi và yêu cầu của hệ thống.
- Người kiểm thử: đối chiếu hành vi thực tế với tiêu chí nghiệm thu.

## 1.4. Thuật ngữ và chữ viết tắt

| Thuật ngữ | Giải thích |
|---|---|
| P2P (Peer-to-Peer) | Mô hình trong đó các peer giao tiếp trực tiếp với nhau; mỗi peer có thể vừa yêu cầu vừa cung cấp tài nguyên. |
| Peer | Một instance đang chạy của ứng dụng, có địa chỉ mạng và cổng lắng nghe riêng khi hoạt động. |
| Peer nguồn | Peer đang cung cấp một tập tin cho peer khác tải xuống. |
| Peer nhận | Peer gửi yêu cầu và nhận tập tin từ peer nguồn. |
| Shared directory | Thư mục cục bộ mà người dùng cho phép ứng dụng cung cấp tập tin từ đó. |
| FR | Functional Requirement — yêu cầu chức năng. |
| NFR | Non-functional Requirement — yêu cầu phi chức năng. |
| MVP | Minimum Viable Product — phiên bản tối thiểu có các chức năng cốt lõi. |
| TCP | Giao thức truyền tải dạng luồng, bảo đảm dữ liệu được truyền theo thứ tự khi kết nối còn hoạt động. |
| SHA-256 | Hàm băm mật mã dùng để kiểm tra tính toàn vẹn dữ liệu. |
| JSON Lines | Định dạng mỗi thông điệp JSON chiếm một dòng, dùng cho giao tiếp điều khiển nội bộ giữa Electron và tiến trình Java. |

## 1.5. Quy ước mức ưu tiên

- **MVP — Bắt buộc:** cần có để chứng minh được nền tảng P2P hoạt động.
- **Bắt buộc cho bản hoàn chỉnh:** cần có trong sản phẩm cuối của đồ án nhưng có thể triển khai sau MVP.
- **Nâng cao:** chỉ triển khai sau khi các yêu cầu cốt lõi đã ổn định và còn đủ thời gian.

---

# 2. Mô tả tổng quan

## 2.1. Bối cảnh và góc nhìn sản phẩm

Sản phẩm là ứng dụng desktop độc lập. Trong mỗi instance, Electron quản lý cửa sổ và giao tiếp giữa giao diện với tiến trình Java. Java P2P Core đảm nhiệm giao tiếp mạng, quản lý peer và xử lý tập tin. Các instance liên lạc trực tiếp với nhau bằng TCP.

Kiến trúc ở mức tổng quan:

1. **React UI:** nhận thao tác và hiển thị dữ liệu/trạng thái cho người dùng.
2. **Electron Main/Preload:** quản lý vòng đời desktop và cung cấp API IPC có giới hạn; chuyển tiếp yêu cầu đến Java P2P Core.
3. **Java P2P Core:** xử lý lệnh điều khiển nội bộ, kết nối TCP giữa các peer, giao thức P2P và thao tác tập tin.
4. **Các peer khác:** những instance ứng dụng khác đang chạy trên cùng máy hoặc trên máy trong mạng có thể truy cập được.

Giao tiếp nội bộ Electron–Java phục vụ điều khiển và theo dõi trạng thái; không được dùng làm đường truyền nội dung tập tin giữa các peer.

## 2.2. Các lớp người dùng và Actor

### Người dùng cục bộ

Người sử dụng ứng dụng desktop. Người dùng có thể khởi chạy peer, chọn thư mục chia sẻ, kết nối đến peer khác, tìm kiếm và tải tập tin.

### Peer từ xa

Một instance ứng dụng khác trên mạng. Peer từ xa có thể gửi yêu cầu kết nối, yêu cầu xem danh sách tập tin được chia sẻ hoặc yêu cầu tải tập tin theo giao thức được hệ thống hỗ trợ.

> Trong Use Case Diagram, **Người dùng** là Actor chính. **Peer từ xa** có thể được mô hình hóa là hệ thống/Actor bên ngoài tương tác với các chức năng mạng; cách biểu diễn cuối cùng sẽ được thống nhất ở bước phân tích Use Case.

## 2.3. Môi trường vận hành

- Hệ điều hành phát triển và kiểm thử chính: Fedora Linux.
- Backend: Java, build bằng Maven.
- Desktop shell: Electron.
- UI: React và TypeScript.
- Kiểm thử mạng: có thể chạy hai hoặc nhiều instance trên cùng máy qua `localhost`; sau đó thử trong mạng LAN nếu có điều kiện.
- Kết nối mạng: TCP/IP.
- Hệ thống tập tin: hệ thống tệp cục bộ của máy đang chạy peer.

Phiên bản cụ thể của Java, Node.js, Electron và các thư viện cần được ghi trong README/thiết lập dự án để môi trường phát triển có thể tái lập.

## 2.4. Ràng buộc thiết kế và triển khai

- Java phải là thành phần xử lý kết nối và truyền nhận dữ liệu P2P.
- React không được truy cập trực tiếp API hệ thống hoặc `child_process`; giao diện chỉ sử dụng API được expose an toàn qua Electron preload.
- Mỗi instance ứng dụng phải có Java P2P Core riêng.
- Nội dung tập tin phải đi trực tiếp giữa peer nguồn và peer nhận, không thông qua dịch vụ tập trung.
- Giai đoạn đầu sử dụng kết nối thủ công bằng IP và cổng; không bắt buộc tự động khám phá peer.
- Không sử dụng database nếu không có yêu cầu cốt lõi cần đến nó.
- Ưu tiên công nghệ và thư viện miễn phí.
- Thiết kế phải tách trách nhiệm UI, cầu nối điều khiển, logic nghiệp vụ, giao tiếp mạng và thao tác tập tin.

## 2.5. Giả định và phụ thuộc

- Người dùng biết địa chỉ IP và cổng của peer mà mình muốn kết nối, hoặc có thể lấy thông tin này từ người vận hành peer đó.
- Hai peer có thể kết nối TCP với nhau qua `localhost` hoặc mạng cho phép lưu lượng giữa chúng.
- Quyền truy cập của hệ điều hành cho phép ứng dụng mở cổng và đọc các tập tin mà người dùng đã chọn chia sẻ.
- Môi trường chạy được cài đặt đúng các runtime cần thiết.
- Trong phiên bản đầu, ứng dụng hướng đến môi trường học tập/kiểm thử tin cậy. Chưa cam kết cung cấp xác thực danh tính peer hoặc mã hóa đầu cuối cho kết nối P2P.

## 2.6. Ngoài phạm vi phiên bản đầu

Các nội dung sau không bắt buộc trong MVP:

- Tự động khám phá peer trên Internet.
- Tracker hoặc máy chủ trung tâm.
- Vượt NAT, port forwarding tự động hoặc bảo đảm hoạt động qua mọi loại mạng.
- Tài khoản người dùng, đăng nhập và quản lý danh tính.
- Mã hóa đầu cuối và chứng thực peer.
- Đồng bộ thư mục hai chiều theo thời gian thực.
- Ứng dụng di động hoặc giao diện web truy cập từ xa.
- Phân phối file qua cloud.
- Cơ sở dữ liệu để lưu trữ dữ liệu nghiệp vụ.

---

# 3. Yêu cầu chức năng (Functional Requirements)

Mỗi yêu cầu có mã duy nhất để có thể liên kết với Use Case và test case. Trừ khi ghi khác, trạng thái các yêu cầu trong tài liệu này là **Dự kiến — chưa xác minh**.

## 3.1. Vòng đời peer và kết nối

| Mã | Yêu cầu | Mô tả/tiêu chí quan sát được | Ưu tiên |
|---|---|---|---|
| FR-01 | Khởi chạy peer | Người dùng có thể khởi động Java P2P Core và yêu cầu ứng dụng bắt đầu lắng nghe trên cổng hợp lệ. Giao diện phải hiển thị trạng thái đang chạy hoặc thông báo lỗi cụ thể. | MVP |
| FR-02 | Cấu hình địa chỉ lắng nghe | Người dùng có thể cấu hình cổng lắng nghe. Mặc định nên dùng loopback cho kiểm thử cục bộ; việc lắng nghe trên giao diện mạng LAN phải là lựa chọn có chủ đích. | MVP |
| FR-03 | Dừng peer | Người dùng có thể dừng peer đang chạy. Hệ thống phải đóng socket lắng nghe và giải phóng các tài nguyên mạng liên quan. | MVP |
| FR-04 | Kết nối peer từ xa | Người dùng có thể nhập IP/hostname và cổng của peer khác để yêu cầu kết nối TCP. Hệ thống phải báo kết quả thành công hoặc lỗi. | MVP |
| FR-05 | Hiển thị danh sách peer | Giao diện hiển thị các peer mà instance hiện tại đang quản lý, tối thiểu gồm địa chỉ, cổng và trạng thái kết nối. | MVP |
| FR-06 | Ngắt kết nối peer | Người dùng có thể yêu cầu ngắt kết nối với một peer. Hệ thống cập nhật trạng thái sau khi xử lý. | MVP |
| FR-07 | Phát hiện mất kết nối | Khi kết nối bị đóng hoặc xảy ra lỗi I/O, peer phải được đánh dấu mất kết nối và giao diện phải nhận được sự kiện trạng thái tương ứng. | Bắt buộc cho bản hoàn chỉnh |

## 3.2. Quản lý thư mục và tìm kiếm tập tin

| Mã | Yêu cầu | Mô tả/tiêu chí quan sát được | Ưu tiên |
|---|---|---|---|
| FR-08 | Chọn thư mục chia sẻ | Người dùng có thể chọn một thư mục cục bộ để cung cấp tập tin cho peer khác. | Bắt buộc cho bản hoàn chỉnh |
| FR-09 | Liệt kê tập tin được chia sẻ | Hệ thống có thể cung cấp danh sách các tập tin cho phép chia sẻ trong phạm vi thư mục đã chọn. | Bắt buộc cho bản hoàn chỉnh |
| FR-10 | Lấy danh sách tập tin từ peer | Người dùng có thể yêu cầu một peer đang kết nối trả về danh sách tập tin được chia sẻ của peer đó. | Bắt buộc cho bản hoàn chỉnh |
| FR-11 | Tìm kiếm tập tin | Người dùng có thể tìm kiếm theo tên tập tin trong danh sách được cung cấp bởi các peer đang kết nối. Kết quả phải chỉ ra peer đang cung cấp tập tin. | Bắt buộc cho bản hoàn chỉnh |
| FR-12 | Cập nhật danh sách | Người dùng có thể yêu cầu tải lại danh sách từ peer; lỗi khi lấy danh sách phải được thể hiện mà không làm hỏng danh sách hoặc trạng thái của các peer khác. | Bắt buộc cho bản hoàn chỉnh |

## 3.3. Truyền nhận tập tin

| Mã | Yêu cầu | Mô tả/tiêu chí quan sát được | Ưu tiên |
|---|---|---|---|
| FR-13 | Yêu cầu tải tập tin | Người dùng có thể chọn một tập tin từ kết quả tìm kiếm và chỉ định nơi lưu tập tin được tải về. | Bắt buộc cho bản hoàn chỉnh |
| FR-14 | Cung cấp tập tin cho peer khác | Peer phải có khả năng tiếp nhận yêu cầu tải tập tin và chỉ cung cấp tập tin thuộc phạm vi cho phép chia sẻ. | Bắt buộc cho bản hoàn chỉnh |
| FR-15 | Hiển thị tiến trình tải | Giao diện hiển thị trạng thái tải như đang chờ, đang tải, hoàn tất, thất bại; khi có dữ liệu phù hợp, hiển thị số byte hoặc phần trăm đã nhận. | Bắt buộc cho bản hoàn chỉnh |
| FR-16 | Xử lý tên đích bị trùng | Khi tập tin đích đã tồn tại, hệ thống phải hỏi/cho phép người dùng chọn hành động an toàn hoặc tạo tên khác; không được âm thầm ghi đè tập tin hiện có. | Bắt buộc cho bản hoàn chỉnh |
| FR-17 | Kiểm tra tính toàn vẹn | Sau khi tải đủ tập tin, hệ thống so sánh SHA-256 của dữ liệu nhận với giá trị xác thực do peer nguồn cung cấp theo giao thức. Nếu không khớp, phải báo tải không hợp lệ và không đánh dấu hoàn tất thành công. | Bắt buộc cho bản hoàn chỉnh |
| FR-18 | Báo lỗi truyền nhận | Hệ thống phải thông báo các tình huống như peer không khả dụng, tập tin không tồn tại, không đủ quyền ghi, kết nối bị ngắt hoặc dữ liệu không hợp lệ. | Bắt buộc cho bản hoàn chỉnh |
| FR-19 | Hủy tác vụ truyền nhận | Người dùng có thể hủy một tác vụ tải đang chạy. Tài nguyên liên quan phải được giải phóng và trạng thái phải được cập nhật. | Bắt buộc cho bản hoàn chỉnh |

## 3.4. Giao tiếp nội bộ Electron–Java

| Mã | Yêu cầu | Mô tả/tiêu chí quan sát được | Ưu tiên |
|---|---|---|---|
| FR-20 | Khởi động Java backend | Electron Main có thể khởi chạy tiến trình Java P2P Core và nhận biết backend đã sẵn sàng hoặc khởi động thất bại. | MVP |
| FR-21 | Gửi lệnh từ giao diện | Giao diện gửi các lệnh được cho phép qua API preload/IPC; Electron chuyển lệnh đến Java backend. Renderer không được gọi trực tiếp API hệ thống. | MVP |
| FR-22 | Nhận phản hồi có tương quan | Mỗi lệnh có mã yêu cầu; phản hồi phải có cùng mã để xác định kết quả của lệnh tương ứng. | MVP |
| FR-23 | Nhận sự kiện trạng thái | Java backend có thể phát sự kiện như peer khởi động, kết nối thành công, ngắt kết nối hoặc tác vụ lỗi để Electron chuyển tới giao diện. | MVP |
| FR-24 | Xử lý backend dừng | Nếu Java backend thoát hoặc bị lỗi, giao diện phải phản ánh trạng thái lỗi/mất kết nối; Electron không được tiếp tục hiển thị trạng thái peer đang hoạt động như thể vẫn bình thường. | MVP |
| FR-25 | Đóng ứng dụng an toàn | Khi người dùng đóng ứng dụng, Electron yêu cầu Java backend dừng an toàn và thực hiện dọn dẹp tiến trình/tài nguyên. | MVP |

## 3.5. Chức năng nâng cao

| Mã | Yêu cầu | Mô tả/tiêu chí quan sát được | Ưu tiên |
|---|---|---|---|
| FR-26 | Chia tập tin thành các phần | Hệ thống chia tập tin thành các phần có thông tin vị trí và độ dài để hỗ trợ truyền theo từng phần. | Nâng cao |
| FR-27 | Tải từ nhiều peer | Khi nhiều peer có cùng tập tin và giao thức hỗ trợ, hệ thống có thể tải các phần khác nhau từ các peer khác nhau rồi ghép lại đúng thứ tự. | Nâng cao |
| FR-28 | Tiếp tục tải sau gián đoạn | Hệ thống lưu đủ thông tin tiến trình để có thể tiếp tục tải những phần chưa hoàn tất sau khi kết nối bị gián đoạn hoặc ứng dụng được khởi động lại. | Nâng cao |
| FR-29 | Theo dõi hoạt động | Giao diện hiển thị log hoạt động ở mức ứng dụng, ví dụ kết nối, ngắt kết nối, bắt đầu/hủy tải và lỗi. Không ghi nội dung tập tin vào log. | Bắt buộc cho bản hoàn chỉnh |

---

# 4. Yêu cầu phi chức năng (Non-functional Requirements)

## 4.1. Khả năng tương thích và triển khai

| Mã | Yêu cầu | Tiêu chí nghiệm thu |
|---|---|---|
| NFR-01 | Môi trường mục tiêu | Ứng dụng phải có hướng dẫn cài đặt và khởi chạy trên Fedora Linux. Các phiên bản runtime và cách build phải được ghi trong README. |
| NFR-02 | Có thể build lặp lại | Dự án Java phải build bằng Gradle; frontend phải có lệnh cài đặt, kiểm tra và build được ghi rõ trong tài liệu. Không phụ thuộc vào đường dẫn tuyệt đối trên máy cá nhân. |
| NFR-03 | Không yêu cầu dịch vụ trả phí | Những chức năng cốt lõi phải chạy mà không cần dịch vụ cloud trả phí hoặc database bên ngoài. |

## 4.2. Hiệu năng và tính đồng thời

| Mã | Yêu cầu | Tiêu chí nghiệm thu |
|---|---|---|
| NFR-04 | Không làm treo giao diện | Tác vụ mạng, đọc/ghi tập tin và tính toán băm phải chạy ngoài luồng giao diện. Khi peer phản hồi chậm hoặc một tập tin đang tải, giao diện vẫn nhận thao tác và có thể cập nhật trạng thái. |
| NFR-05 | Kết nối có giới hạn thời gian | Thử kết nối đến địa chỉ/cổng không có dịch vụ phải kết thúc bằng thông báo lỗi trong thời gian hữu hạn; mục tiêu ban đầu là không quá 5 giây với timeout kết nối được cấu hình phù hợp. |
| NFR-06 | Tác vụ đồng thời | Mục tiêu kiểm thử cho bản hoàn chỉnh là xử lý ít nhất hai kết nối/tác vụ truyền nhận đồng thời mà không làm dừng toàn bộ ứng dụng. |
| NFR-07 | Cập nhật tiến trình | Khi tác vụ tải diễn ra, giao diện cập nhật tiến trình định kỳ hoặc khi có thay đổi đáng kể; việc cập nhật không được gửi sự kiện dày đặc đến mức làm giao diện mất phản hồi. |

## 4.3. Tính đúng đắn và độ tin cậy

| Mã | Yêu cầu | Tiêu chí nghiệm thu |
|---|---|---|
| NFR-08 | Tính toàn vẹn dữ liệu | Với tập tin tải hoàn tất thành công, SHA-256 của tập tin nhận phải trùng giá trị kỳ vọng từ nguồn. Tập tin sai băm không được báo thành công. |
| NFR-09 | Xử lý lỗi | Lỗi đầu vào, lỗi mạng, lỗi hệ thống tệp và backend thoát bất thường phải được xử lý có thông báo; không được để exception không xử lý làm ứng dụng dừng ngoài dự kiến. |
| NFR-10 | Giải phóng tài nguyên | Khi dừng peer, hủy tác vụ hoặc đóng ứng dụng, các socket, executor và tiến trình con liên quan phải được đóng/dừng đúng cách; không được để listener tiếp tục chiếm cổng sau khi dừng hoàn tất. |
| NFR-11 | Giao thức không phụ thuộc ranh giới gói TCP | Việc phân tích thông điệp phải dựa trên cơ chế framing đã định nghĩa, không giả định một lần đọc socket tương ứng một thông điệp hoàn chỉnh. |

## 4.4. An toàn và bảo mật cơ bản

| Mã | Yêu cầu | Tiêu chí nghiệm thu |
|---|---|---|
| NFR-12 | Giới hạn phạm vi chia sẻ | Peer chỉ được cung cấp tập tin nằm trong thư mục chia sẻ được người dùng cho phép. Đường dẫn nhận từ mạng phải được kiểm tra để ngăn truy cập vượt thư mục chia sẻ. |
| NFR-13 | Xác thực dữ liệu đầu vào | Lệnh nội bộ và thông điệp mạng phải được kiểm tra cấu trúc, kiểu dữ liệu, giá trị cổng, kích thước và trạng thái hợp lệ trước khi xử lý. |
| NFR-14 | Cấu hình Electron an toàn | Electron phải bật `contextIsolation`, tắt `nodeIntegration` trong renderer và chỉ expose các API cần thiết qua preload. |
| NFR-15 | Mặc định giảm phơi bày mạng | Chế độ mặc định phục vụ kiểm thử nên lắng nghe trên loopback. Khi cho phép peer khác trong LAN kết nối, người dùng phải chủ động bật/chọn chế độ phù hợp và được thông báo rằng peer khác có thể truy cập các tập tin đã chia sẻ. |
| NFR-16 | Giới hạn bảo mật được công bố | Tài liệu phải nêu rõ nếu phiên bản đầu chưa có xác thực peer và mã hóa đầu cuối; không quảng bá phiên bản này là an toàn khi mở cổng ra Internet công cộng. |

## 4.5. Khả năng bảo trì và sử dụng

| Mã | Yêu cầu | Tiêu chí nghiệm thu |
|---|---|---|
| NFR-17 | Phân tách trách nhiệm | UI, Electron bridge, nghiệp vụ P2P, giao thức mạng và thao tác tập tin phải được tổ chức thành các thành phần có trách nhiệm rõ ràng. |
| NFR-18 | Dễ kiểm thử | Các logic cốt lõi có thể kiểm thử độc lập ở mức phù hợp; có test tự động cho các thành phần quan trọng và hướng dẫn kiểm thử thủ công hai peer. |
| NFR-19 | Giao diện dễ hiểu | Các trạng thái khởi động, đang chạy, kết nối, đang tải, hoàn tất và lỗi phải có nhãn/thông báo phân biệt rõ ràng. |
| NFR-20 | Tài liệu kỹ thuật | README phải nêu cách cài đặt, build, chạy và kiểm thử. Tài liệu kiến trúc/giao thức phải phản ánh cách hệ thống thực sự được thiết kế. |

---

# 5. Quy tắc nghiệp vụ

| Mã | Quy tắc |
|---|---|
| BR-01 | Mỗi peer phải sử dụng một cổng lắng nghe không bị chiếm dụng trên máy đang chạy. Khi cổng không khả dụng, hệ thống phải thông báo và không tuyên bố peer đã khởi chạy thành công. |
| BR-02 | Một peer có thể vừa chủ động kết nối tới peer khác, vừa chấp nhận các kết nối đến theo cấu hình cho phép. |
| BR-03 | Chỉ tập tin thuộc thư mục chia sẻ được chọn mới được cung cấp cho peer khác. Không cung cấp tập tin bên ngoài thư mục đó chỉ vì peer từ xa gửi đường dẫn tùy ý. |
| BR-04 | Kết quả tìm kiếm chỉ bao gồm các tập tin mà ứng dụng nhận được từ các peer đã kết nối và có quyền truy vấn. Không tuyên bố tìm kiếm toàn bộ mạng Internet. |
| BR-05 | Nội dung tập tin được truyền trực tiếp giữa peer nguồn và peer nhận qua TCP; Electron chỉ điều khiển ứng dụng và hiển thị trạng thái. |
| BR-06 | Tập tin chỉ được đánh dấu tải thành công sau khi nhận đủ dữ liệu và hoàn thành các kiểm tra tính toàn vẹn được yêu cầu. |
| BR-07 | Không âm thầm ghi đè tập tin đích đã tồn tại. Người dùng phải được thông báo hoặc áp dụng hành động an toàn đã xác định. |
| BR-08 | Khi peer ngắt kết nối, các tác vụ phụ thuộc vào kết nối đó phải chuyển sang trạng thái lỗi/hủy phù hợp; ứng dụng không được treo toàn bộ. |
| BR-09 | Trong MVP, người dùng chỉ kết nối peer bằng IP/hostname và cổng được cung cấp thủ công. Không có yêu cầu khám phá peer tự động. |
| BR-10 | Thông điệp điều khiển Electron–Java và thông điệp TCP giữa các peer là hai loại giao tiếp khác nhau, có framing và xử lý lỗi riêng. |

---

# 6. Đặc tả giao diện bên ngoài

## 6.1. Giao diện người dùng

Giao diện desktop dự kiến gồm các khu vực:

- **Tổng quan/trạng thái:** hiển thị trạng thái Java backend và peer cục bộ.
- **Điều khiển peer:** nhập cổng/địa chỉ lắng nghe, nút khởi động và dừng peer.
- **Kết nối peer:** nhập IP/hostname và cổng peer đích; hiển thị kết quả.
- **Danh sách peer:** hiển thị địa chỉ, cổng và trạng thái kết nối.
- **Tập tin chia sẻ/tìm kiếm:** chọn thư mục chia sẻ, truy vấn danh sách tập tin và tìm theo tên.
- **Tác vụ tải:** hiển thị tập tin, peer nguồn, nơi lưu, tiến trình và trạng thái.
- **Nhật ký hoạt động:** hiển thị sự kiện và lỗi quan trọng ở mức ứng dụng.

Trong MVP, chỉ cần hoàn thiện giao diện cho quản lý backend, khởi chạy peer, kết nối/ngắt kết nối peer và trạng thái kết nối. Các màn hình chức năng chưa triển khai phải được ghi rõ hoặc để ở trạng thái không khả dụng; không hiển thị dữ liệu giả như kết quả thật.

## 6.2. Giao diện phần mềm

- **React ↔ Electron:** giao tiếp qua API giới hạn được expose bằng preload và IPC an toàn.
- **Electron ↔ Java:** dự kiến dùng JSON Lines qua standard input/output của tiến trình Java. Mỗi lệnh có mã yêu cầu; response liên hệ với mã đó; sự kiện bất đồng bộ được phân biệt với response.
- **Java peer ↔ Java peer:** dùng TCP/IP và giao thức ứng dụng có framing rõ ràng. Giao thức phải phân biệt thông điệp điều khiển với luồng dữ liệu nhị phân của tập tin khi triển khai truyền file.

Chi tiết schema thông điệp, framing, timeout và mã lỗi được quản lý trong tài liệu thiết kế giao thức riêng (`docs/protocol.md`) và phải nhất quán với yêu cầu này.

## 6.3. Giao diện hệ thống tệp

Ứng dụng cần đọc danh sách và nội dung tập tin trong thư mục được người dùng cho phép chia sẻ, đồng thời ghi tập tin tải về vào vị trí do người dùng chọn. Mọi lỗi quyền truy cập, thư mục không tồn tại, dung lượng không đủ hoặc tập tin đích bị trùng phải được xử lý có thông báo.

---

# 7. Use Case tổng quan dự kiến

Phần này chỉ liệt kê Use Case ở mức cao để dẫn tới bước UML tiếp theo; đặc tả luồng chính/luồng thay thế sẽ được viết trong tài liệu Use Case riêng.

| Mã UC | Tên Use Case | Actor chính | Yêu cầu liên quan |
|---|---|---|---|
| UC-01 | Khởi chạy peer | Người dùng | FR-01, FR-02, FR-20 |
| UC-02 | Dừng peer | Người dùng | FR-03, FR-25 |
| UC-03 | Kết nối peer | Người dùng | FR-04, FR-05, FR-21–FR-23 |
| UC-04 | Ngắt kết nối peer | Người dùng | FR-06, FR-07 |
| UC-05 | Chọn thư mục chia sẻ | Người dùng | FR-08, FR-14 |
| UC-06 | Xem/tìm kiếm tập tin | Người dùng | FR-09–FR-12 |
| UC-07 | Tải tập tin | Người dùng | FR-13, FR-15–FR-19 |
| UC-08 | Cung cấp tập tin cho peer | Peer từ xa | FR-14, FR-18 |
| UC-09 | Theo dõi nhật ký hoạt động | Người dùng | FR-29 |
| UC-10 | Tiếp tục tải tập tin | Người dùng | FR-26–FR-28 |

---

# 8. Tiêu chí nghiệm thu tổng thể

## 8.1. Nghiệm thu MVP

MVP được xem là đạt khi đáp ứng tối thiểu các điều kiện sau:

1. Dự án Java build thành công và các test tự động đã cấu hình chạy được.
2. Desktop Electron khởi chạy được Java P2P Core và nhận biết trạng thái backend.
3. Người dùng có thể khởi động và dừng peer trên một cổng hợp lệ.
4. Hai instance ứng dụng riêng biệt có thể chạy trên cùng máy với hai cổng khác nhau, ví dụ `5001` và `5002`.
5. Một peer có thể kết nối đến peer còn lại bằng `localhost` hoặc địa chỉ phù hợp; trạng thái kết nối được hiển thị ở giao diện.
6. Khi nhập địa chỉ/cổng không có dịch vụ lắng nghe, hệ thống trả về thông báo lỗi và không bị treo.
7. Khi dừng peer hoặc đóng ứng dụng, socket và tiến trình Java được dọn dẹp đúng cách trong điều kiện bình thường.
8. Tài liệu README hướng dẫn được cách cài đặt, build và chạy; tài liệu SRS không khẳng định những chức năng chưa có là đã hoàn thành.

## 8.2. Nghiệm thu bản hoàn chỉnh

Ngoài các tiêu chí MVP, bản hoàn chỉnh cần được kiểm thử các chức năng chọn thư mục chia sẻ, liệt kê và tìm kiếm tập tin, truyền nhận tập tin, tiến trình tải, xử lý lỗi và kiểm tra SHA-256. Những yêu cầu nâng cao chỉ được tuyên bố hoàn thành khi có kiểm thử phù hợp chứng minh việc chia phần, tải nhiều nguồn hoặc tiếp tục tải hoạt động đúng.

---

# 9. Ma trận truy vết yêu cầu ở mức ban đầu

| Nhóm yêu cầu | Use Case dự kiến | Bằng chứng kiểm thử dự kiến |
|---|---|---|
| FR-01–FR-03 | UC-01, UC-02 | Khởi động peer trên cổng hợp lệ; thử cổng đang bị chiếm; dừng peer và xác nhận cổng được giải phóng. |
| FR-04–FR-07 | UC-03, UC-04 | Kết nối hai peer; kết nối sai cổng; ngắt kết nối; mô phỏng peer đóng đột ngột. |
| FR-08–FR-12 | UC-05, UC-06 | Chọn thư mục; lấy danh sách; tìm tập tin có/không có; xác nhận chỉ truy cập trong phạm vi chia sẻ. |
| FR-13–FR-19 | UC-07, UC-08 | Tải tập tin mẫu; kiểm tra kích thước và SHA-256; thử file không tồn tại, ngắt mạng, đích trùng và hủy tải. |
| FR-20–FR-25 | UC-01–UC-04 | Kiểm tra lệnh/response JSON Lines, request ID, backend thoát bất thường và đóng ứng dụng. |
| FR-26–FR-28 | UC-10 | Kiểm tra ghép đúng thứ tự, tải từ nhiều nguồn và tiếp tục tải sau khi bị gián đoạn. |
| NFR-04–NFR-07 | Các UC có tác vụ dài | Kiểm tra giao diện vẫn phản hồi và tối thiểu hai tác vụ đồng thời. |
| NFR-12–NFR-16 | UC-05, UC-07, UC-08 | Thử đường dẫn vượt thư mục chia sẻ, thông điệp không hợp lệ và xác nhận chế độ lắng nghe mặc định. |

---

# 10. Các vấn đề cần xác nhận trước khi chốt SRS

Những mục sau được ghi nhận là quyết định còn mở. Chúng không ngăn cản việc thiết kế MVP nhưng cần được quyết định trước khi triển khai phần liên quan:

1. **Phạm vi tìm kiếm:** chỉ tìm trong peer đang kết nối trực tiếp (mặc định đề xuất) hay cần tìm gián tiếp qua các peer khác.
2. **Phạm vi mạng:** chỉ localhost/LAN hay có mục tiêu truy cập qua Internet. Bản hiện tại chỉ cam kết localhost/LAN trong điều kiện mạng phù hợp.
3. **Chính sách chia sẻ:** cho phép chia sẻ cả thư mục đệ quy hay chỉ các tập tin ở cấp thư mục đã chọn.
4. **Tập tin trùng tên:** hỏi người dùng ghi đè, bỏ qua hay tự động tạo tên mới; hành vi mặc định phải an toàn.
5. **Bảo mật:** có yêu cầu xác thực peer hoặc mã hóa kết nối hay không. Bản hiện tại chưa đặt chúng trong MVP và không nên mở cổng ra Internet công cộng.
6. **Tải nhiều nguồn/tiếp tục tải:** giữ là tính năng nâng cao, chỉ triển khai khi các chức năng truyền file cơ bản và kiểm tra SHA-256 đã ổn định.
7. **Yêu cầu của giảng viên:** bổ sung các mẫu biểu, sơ đồ hoặc quy chuẩn tài liệu bắt buộc nếu môn học có yêu cầu riêng.

---

# 11. Lịch sử phiên bản

| Phiên bản | Ngày | Nội dung |
|---|---|---|
| 0.1 — Draft | 15/10/2026 | Khởi tạo phạm vi, yêu cầu chức năng, yêu cầu phi chức năng, quy tắc nghiệp vụ và tiêu chí nghiệm thu ban đầu. Chưa được xác nhận bằng triển khai thực tế. |
