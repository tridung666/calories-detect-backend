# Quên mật khẩu và đổi mật khẩu bằng OTP email

Các API dưới đây dùng `Content-Type: application/json`. Hai luồng dùng chung
API xác nhận `/api/auth/reset-password`. Chỉ tài khoản ACTIVE, đã xác minh email và có mật khẩu
local mới nhận được mã. Tài khoản chỉ đăng nhập Google tiếp tục dùng Google;
tài khoản chưa xác minh email dùng `/api/auth/resend-otp` và `/api/auth/verify-email` trước.

## 1a. Quên mật khẩu: yêu cầu hoặc gửi lại OTP

`POST /api/auth/forgot-password`

Không cần Bearer token hoặc mật khẩu hiện tại.

```json
{"email":"user@example.com"}
```

HTTP 200:

```json
{
  "success": true,
  "code": 200,
  "message": "Success",
  "data": "If the account is eligible and the request limit allows it, a password reset OTP will be sent to your email"
}
```

Email hợp lệ về định dạng luôn nhận phản hồi chung trên, kể cả khi email không
tồn tại, tài khoản không đủ điều kiện, đã vượt giới hạn gửi hoặc SMTP lỗi.
HTTP 200 không khẳng định email đã được gửi. Gọi lại cùng API để gửi lại mã:
chờ ít nhất 60 giây, tối đa 5 mã mỗi giờ cho mỗi tài khoản, tính chung cả
`forgot-password` và `change-password/request`.

OTP có 6 chữ số, hết hạn sau 5 phút, lưu trong database dưới dạng BCrypt hash
với purpose `PASSWORD_RESET`. Gửi lại thành công vô hiệu hóa mã reset trước đó.
SMTP lỗi rollback lần gửi mới để giữ mã cũ còn hiệu lực.

## 1b. Đổi mật khẩu khi đã đăng nhập: yêu cầu OTP

`POST /api/auth/change-password/request`

Header: `Authorization: Bearer <accessToken>`

```json
{"currentPassword":"CurrentPassword123!"}
```

Backend lấy tài khoản từ JWT, kiểm tra mật khẩu hiện tại rồi gửi OTP cùng loại
`PASSWORD_RESET`. Các trường email/userId gửi thêm không thay đổi tài khoản nhận mã.
Sau đó gọi API xác nhận chung ở bước 2. Sai mật khẩu hiện tại trả HTTP 400,
code `11002`; vượt cooldown/giới hạn giờ trả HTTP 429, code `11010`.

Cả hai API yêu cầu OTP dùng chung giới hạn và mã mới nhất. Gửi lại qua API này
sẽ vô hiệu hóa mã trước đó được gửi qua API còn lại.

## 2. Xác nhận chung cho cả hai luồng

`POST /api/auth/reset-password`

Không cần Bearer token. Email và OTP xác định tài khoản cần cập nhật; luôn dùng
email của tài khoản đã yêu cầu mã ở bước 1a hoặc 1b.

```json
{
  "email": "user@example.com",
  "otp": "012345",
  "newPassword": "NewPassword123!",
  "confirmPassword": "NewPassword123!"
}
```

OTP trong ví dụ là giá trị minh họa; lấy mã thực từ email và truyền dạng chuỗi
để giữ chữ số 0 ở đầu. Mật khẩu phải có 8–72 ký tự, tối đa 72 byte UTF-8,
không chỉ gồm khoảng trắng; hai trường mật khẩu phải khớp nhau.

HTTP 200:

```json
{
  "success": true,
  "code": 200,
  "message": "Success",
  "data": "Password reset successfully. Please log in again"
}
```

Sau thành công, mật khẩu được hash bằng BCrypt trong provider LOCAL, OTP bị tiêu thụ
và mọi refresh token của tài khoản bị thu hồi trong cùng transaction. Người dùng gọi `/api/auth/login` để lấy token mới.
Access token đã cấp vẫn còn hiệu lực đến khi hết hạn theo cơ chế JWT hiện có.
Reset không so sánh với mật khẩu cũ; xác thực bằng OTP là bắt buộc ngay cả khi
người dùng nhập lại cùng mật khẩu.

## Chuyển từ API xác nhận cũ

`POST /api/auth/change-password/confirm` đã được bỏ. Frontend đổi mật khẩu chuyển
sang `/api/auth/reset-password`, thêm trường `email` vào body cùng `otp`,
`newPassword`, `confirmPassword`. Thông báo thành công chung là
`Password reset successfully. Please log in again`.

Luồng đổi mật khẩu cũng cho phép nhập lại mật khẩu cũ sau khi xác thực OTP,
theo quy tắc của API xác nhận chung.

## Lỗi

| HTTP | Code | Trường hợp |
| --- | --- | --- |
| 400 | 400 | JSON/email/OTP sai định dạng, thiếu trường hoặc mật khẩu sai độ dài |
| 400 | 11006 | OTP sai, hết hạn, đã dùng, nhập sai 5 lần, khác mục đích/tài khoản; tài khoản không đủ điều kiện hoặc không tồn tại |
| 400 | 11003 | Xác nhận mật khẩu không khớp |
| 400 | 11012 | Mật khẩu không hợp lệ, bao gồm vượt 72 byte UTF-8 |

Các lần nhập sai OTP được lưu ngay cả khi trả lỗi. Sau 5 lần sai phải yêu cầu mã mới.
Lỗi định dạng/xác nhận mật khẩu không tiêu thụ OTP. Khóa dòng user ngăn gửi hoặc
xác nhận đồng thời vượt giới hạn/dùng mã hai lần, đồng thời tuần tự hóa với login,
refresh token và đổi mật khẩu.

## Cấu hình và kiểm thử

Dùng cấu hình SMTP hiện có (`MAIL_USERNAME`, `MAIL_PASSWORD`) và schema OTP từ
migration V6 và bảng `auth_providers` từ V7. Swagger mô tả cả hai endpoint.

`PasswordResetIntegrationTest` dùng PostgreSQL thật và mock SMTP, kiểm tra HTTP
công khai, hash/expiry, thu hồi token, chống dùng lại mã, phân tách purpose/user,
giới hạn gửi/nhập sai, rollback khi SMTP/database lỗi và request đồng thời.
Chạy toàn bộ suite với database test riêng đã tạo trước:

```sh
DB_URL=jdbc:postgresql://localhost:5433/calories_password_reset_test_20260922 ./gradlew test --console=plain
```

Kiểm thử tự động không gửi email thực; cần kiểm tra inbox với cấu hình SMTP thực
khi chạy ứng dụng.
