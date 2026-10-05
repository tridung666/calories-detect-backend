# API cầu nối và luồng phân tích meal trên frontend

Frontend gọi backend Spring bằng access token hiện có. Spring kiểm tra tài khoản
và quyền sở hữu meal, lấy URL ảnh từ database rồi gọi AI nội bộ. AI không nhận
JWT hoặc meal ID, không truy cập database và không tự lưu kết quả.

## Các API trong luồng

Các endpoint backend đều cần `Authorization: Bearer <accessToken>` và trả
`BaseResponse`: `{success, code, message, data}`. Phân biệt `/api/meal` số ít
cho CRUD hiện có với `/api/meals` số nhiều cho ảnh và phân tích.

| Bên gọi | Method và endpoint | Input | Kết quả |
| --- | --- | --- | --- |
| FE → Spring | `POST /api/meal` | JSON `mealType`, `mealDate` | Meal mới, lấy `data.id` |
| FE → Spring | `PUT /api/meals/{mealId}/image` | Multipart field `file` | Meal với `imageUrl` Cloudinary |
| FE → Spring | `POST /api/meals/{mealId}/analyze` | Không cần body | `data.mealId`, danh sách dự đoán; chưa lưu món |
| Spring → AI | `POST /api/v1/meals/analyze` | JSON chỉ có `imageUrl` | JSON chỉ có `items`, không bọc BaseResponse |
| FE → Spring | `POST /api/meals/{mealId}/confirm-analysis` | JSON `items` đã chỉnh sửa | Meal kèm toàn bộ món đã lưu |
| FE → Spring | `GET /api/meal/{mealId}` | Không body | Thông tin meal và ảnh |
| FE → Spring | `GET /api/meal/{mealId}/items` | Không body | Danh sách món đã lưu |

`GET /health` và `GET /ready` của AI dành cho vận hành; FE sử dụng kết quả
API backend để xử lý trạng thái phân tích. Readiness không gọi model.

## Contract Spring → AI

Spring dùng `AI_SERVICE_BASE_URL` để gọi endpoint nội bộ:

```json
{"imageUrl":"https://res.cloudinary.com/example/image/upload/meal.jpg"}
```

`imageUrl` là URL được backend lưu sau upload, không lấy từ body FE.
AI trả:

```json
{
  "items": [
    {
      "name": "Ức gà",
      "estimatedGrams": 150,
      "calories": 248,
      "protein": 46.5,
      "carbohydrate": 0,
      "fat": 5.4,
      "confidence": 0.91
    }
  ]
}
```

Spring kiểm tra tên, khối lượng, dinh dưỡng và confidence, rồi gắn meal ID từ
path vào response FE: `data: {mealId, items}`. Dự đoán phải phù hợp giới hạn
lưu trữ: tên tối đa 255 ký tự, khối lượng dương và tối đa 99999999.99 g,
dinh dưỡng không âm và tối đa 2147483647; khối lượng/dinh dưỡng tối đa hai
chữ số thập phân. Confidence từ 0 đến 1.

AI dùng URL ảnh làm đầu vào cho model; URL phải tải được từ Internet.
Spring không giữ transaction database trong lúc chờ AI. Không có draft ID
hay bản nháp phía server. Endpoint analyze không tạo, sửa hoặc xóa meal item.

## Cách frontend hoạt động

1. Người dùng chọn ngày, loại bữa ăn, chụp ảnh hoặc chọn ảnh. FE có thể preview
   bằng object URL, nhưng phải upload file thật trước khi phân tích.
2. Với meal mới, gọi `POST /api/meal`, ví dụ
   `{"mealType":"LUNCH","mealDate":"2026-10-05"}` và giữ `data.id`.
   Với meal có sẵn, dùng ID hiện tại.
3. Gửi `FormData` có field `file` lên API ảnh. Để browser đặt multipart boundary;
   không tự đặt header `Content-Type: application/json`. Chấp nhận JPEG/PNG/WebP,
   tối đa 5 MiB. Chỉ chuyển sang phân tích sau khi upload thành công.
4. Bấm “Phân tích”, gọi analyze không body. Hiện trạng thái đang phân tích và
   khóa nút khi request đang chạy. Dùng timeout riêng khoảng 90 giây với backend
   75 giây/AI 60 giây. Tránh tự retry vì mỗi lần phân tích có thể gọi model trả phí.
5. Giữ `data.items` trong state của màn hình, cho sửa tên, khối lượng, calories
   và các macro, thêm/xóa món. `estimatedGrams` là gợi ý ban đầu cho `quantityGrams`.
   Confidence chỉ để người dùng đánh giá gợi ý, không gửi khi lưu.
6. Bấm “Lưu bữa ăn”, validate rồi gửi confirm-analysis. Khóa nút lưu trong lúc
   request đang chạy; chỉ báo đã lưu khi backend trả thành công.
7. Dùng meal và items trong response để cập nhật UI; invalidate/refetch các query
   meal, meal items và dashboard theo cơ chế hiện có của FE.

Nếu người dùng thay ảnh hoặc chuyển meal trong lúc đang phân tích, FE bỏ kết quả
cũ khi request trả về. Abort phía FE không đảm bảo model phía server đã dừng.
Hủy màn hình review hoặc reload trước khi confirm sẽ mất dự đoán trong state;
meal/ảnh đã tạo hoặc upload vẫn còn trên server. Chỉ gọi API xóa meal khi người
dùng thực sự chọn xóa.

## Xác nhận và mapping field

Request lưu ví dụ:

```json
{
  "items": [
    {
      "name": "Ức gà",
      "quantityGrams": 180,
      "calories": 297.6,
      "protein": 55.8,
      "carbohydrate": 0,
      "fat": 6.48
    }
  ]
}
```

| Field trên màn hình dự đoán | Field gửi confirm | Field món đã lưu |
| --- | --- | --- |
| `name` | `name` | `inputName` |
| `estimatedGrams` (sửa được) | `quantityGrams` | `quantityGrams` |
| `calories` | `calories` | `calories` |
| `protein` | `protein` | `proteinGrams` |
| `carbohydrate` | `carbohydrate` | `carbohydrateGrams` |
| `fat` | `fat` | `fatGrams` |
| `confidence` | Không gửi | Không lưu |

Các giá trị dinh dưỡng là tổng cho khẩu phần, không phải trên 100 g.
Backend lưu đúng số đã gửi và không tự tính lại khi đổi khối lượng. Nếu FE
muốn tự scale, lấy dinh dưỡng gốc × khối lượng mới / khối lượng ước lượng gốc,
làm tròn hai chữ số, rồi cho người dùng chỉnh sửa trước khi lưu. Ví dụ 150 g
→ 180 g là nhân 1.2 như request trên. Giữ giá trị gốc để tránh sai số tích lũy.

Confirm yêu cầu danh sách không rỗng, thay thế **toàn bộ** món cũ trong một
transaction. Lỗi validate hoặc lỗi lưu sẽ giữ món cũ. Gửi lại không append nhưng
sinh item ID mới. Confirm không gọi AI; có thể gửi món nhập tay mà chưa analyze
hoặc chưa có ảnh. API xác thực ownership và dữ liệu, không kiểm chứng các số
người dùng chỉnh sửa có đúng với dự đoán hay không.

Response confirm có `data: {id, mealType, mealDate, imageUrl, items}`;
items có ID thật và field như cột cuối bảng trên. GET meal hiện không gộp
items; khi mở lại màn hình chi tiết, FE tải thêm GET items.

## Xử lý lỗi trên FE

| HTTP / code | Ý nghĩa | Cách xử lý |
| --- | --- | --- |
| 401 | Session thiếu/hết hạn | Dùng luồng refresh hiện có; đăng nhập nếu refresh thất bại |
| 404 / 14000 | Meal thiếu hoặc không thuộc user | Thông báo không tìm thấy, tải lại danh sách |
| 400 / 15000 | File không hợp lệ | Yêu cầu chọn JPEG/PNG/WebP hợp lệ |
| 413 / 15001 | Ảnh quá 5 MiB | Yêu cầu ảnh nhỏ hơn |
| 503 / 15002 | Upload Cloudinary lỗi | Cho người dùng thử upload lại |
| 400 / 16000 | Meal chưa có ảnh | Upload ảnh trước |
| 503 / 16001 | AI/provider không sẵn sàng | Giữ màn hình, cho thử lại hoặc nhập tay |
| 504 / 16002 | Hết thời gian phân tích | Cho thử lại theo thao tác người dùng hoặc nhập tay |
| 502 / 16003 | Dự đoán/contract không hợp lệ | Không lưu kết quả; thử lại hoặc nhập tay |
| 422 / 16004 | Không nhận diện được món | Chụp rõ hơn hoặc nhập tay |
| 422 / 16005 | Không đọc được ảnh | Upload ảnh khác |
| 400 / 400 | Confirm thiếu/sai dữ liệu | Giữ bản chỉnh sửa, báo lỗi để sửa |

Không forward lỗi chi tiết provider hoặc OpenAI key ra FE. Khi lưu lỗi hoặc
mạng mất kết nối, giữ dữ liệu review để người dùng có thể sửa/thử lại.
