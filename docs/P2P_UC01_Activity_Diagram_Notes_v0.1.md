# Activity Diagram — UC-01: Khởi chạy peer

| Thuộc tính | Giá trị |
|---|---|
| Mã tài liệu | AD-UC01-001 |
| Phiên bản | 0.1 — Draft |
| Use Case liên quan | UC-01 — Khởi chạy peer |
| Tài liệu nguồn | `docs/SRS.md`, `docs/use-case-specifications.md` |
| Trạng thái | Bản nháp để rà soát trước khi thiết kế chi tiết |

## 1. Mục đích

Activity Diagram mô tả luồng khởi chạy peer từ khi người dùng nhấn **Start Peer** đến khi giao diện xác nhận trạng thái thực tế. Sơ đồ làm rõ các nhánh đầu vào không hợp lệ, backend không khả dụng, peer đã chạy, lỗi bind socket và timeout ở cầu nối Electron–Java.

## 2. Swimlane / Partition

- **Người dùng:** nhập cổng, chọn địa chỉ/interface nếu được hỗ trợ và yêu cầu khởi chạy.
- **React UI:** kiểm tra đầu vào cơ bản và hiển thị kết quả.
- **Electron Main / Bridge:** chuyển lệnh qua IPC, gửi JSON Lines đến Java và xử lý response theo `requestId`.
- **Java P2P Core:** xác thực lại cấu hình, mở socket lắng nghe, khởi chạy vòng lặp accept và báo trạng thái.

Các partition này là thành phần nội bộ của một ứng dụng desktop, không phải Actor riêng trong Use Case Diagram.

## 3. Các nhánh cần chú ý

1. **Đầu vào không hợp lệ:** dừng trước khi gửi command đến Java.
2. **Java backend không sẵn sàng:** giao diện hiển thị lỗi; không giả lập trạng thái `RUNNING`.
3. **Peer đã chạy:** không mở thêm listener; trả trạng thái hiện tại để thao tác có thể lặp an toàn.
4. **Cấu hình không hợp lệ hoặc bind thất bại:** Java trả lỗi và dọn dẹp tài nguyên đã tạo một phần.
5. **Timeout khi chờ response:** trạng thái được xem là chưa xác định; Electron cần đồng bộ lại với Java thay vì tự kết luận thành công hay thất bại.
6. **Chỉ xác nhận thành công khi trạng thái thực tế là `RUNNING`:** UI không được dựa riêng vào việc đã gửi command.

## 4. Quyết định thiết kế cần giữ nhất quán

- Kiểm tra dữ liệu ở UI chỉ nhằm phản hồi nhanh; Java vẫn phải xác thực lại vì Java là nơi thực sự mở socket.
- Mọi thao tác socket không được chặn giao diện React/Electron.
- Vòng lặp `accept` chạy ở luồng nền và phải có cách dừng khi peer được Stop.
- Standard output của tiến trình Java dành cho JSON Lines; log kỹ thuật phải ghi ra standard error để không làm hỏng giao thức.
- Nếu response bị timeout sau khi command đã được gửi, không nên gửi lặp lại lệnh khởi chạy một cách mù quáng; cần truy vấn trạng thái trước.

## 5. Cách dùng trong repository

Đặt file nguồn PlantUML tại `docs/diagrams/activity-uc01-start-peer.puml` và tài liệu này tại `docs/activity-uc01-start-peer.md`.

Có thể mở file `.puml` trong VS Code với extension PlantUML để xem/render sơ đồ. File `.puml` là nguồn có thể chỉnh sửa; khi thiết kế thay đổi, hãy cập nhật nguồn này cùng đặc tả UC-01.

## 6. Phạm vi hiện tại

Sơ đồ chỉ mô tả UC-01. Nó không mô tả toàn bộ vòng đời Java backend, kết nối đến peer từ xa hoặc chức năng truyền tập tin. Các nội dung đó sẽ có sơ đồ riêng khi đến Use Case tương ứng.
