# Chạy backend kết nối AI

Spring quản lý đăng nhập, quyền sở hữu meal và database. AI chỉ nhận URL ảnh,
phân tích bằng model rồi trả dự đoán. Contract và luồng frontend nằm trong
[ai-meal-analysis.md](ai-meal-analysis.md) và [meal-analysis-frontend.md](meal-analysis-frontend.md).

## Chạy trực tiếp trên máy

Trong repo AI, cấu hình `.env` với `OPENAI_API_KEY`, `OPENAI_MODEL` và
`OPENAI_TIMEOUT_SECONDS=60`, rồi khởi động:

```sh
uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Trong `.env` của backend:

```dotenv
AI_SERVICE_BASE_URL=http://localhost:8000
AI_SERVICE_CONNECT_TIMEOUT=3s
AI_SERVICE_RESPONSE_TIMEOUT=75s
```

Chạy `./gradlew bootRun`. Backend còn cần PostgreSQL và cấu hình auth,
SMTP, Cloudinary hiện có. OpenAI key nằm trong AI service.
Giữ timeout backend lớn hơn timeout AI; frontend nên chờ ít nhất 90 giây
cho request phân tích khi sử dụng cấu hình mặc định.

## Chạy bằng Docker Compose

Sử dụng `compose.yaml` hiện có: chạy backend, PostgreSQL và AI trên cùng network.
Backend gọi `http://ai:8000`; service AI không publish port trong stack này.
Với hai checkout ở cạnh nhau, đường dẫn mặc định là `../calories-detect-AI`.
Với các worktree hiện tại, thêm vào `.env` backend:

```dotenv
AI_BUILD_CONTEXT=/Users/nguyentridung/orca/workspaces/calories-detect-AI/tuskfish
AI_ENV_FILE=/Users/nguyentridung/orca/workspaces/calories-detect-AI/tuskfish/.env
```

```sh
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 180
```

Compose ghi đè URL AI trong backend thành `http://ai:8000`, kể cả khi `.env`
đang đặt `localhost`. AI cần cấu hình provider và truy cập Internet; OpenAI
phải tải được URL ảnh Cloudinary. `GET /health` kiểm tra tiến trình;
`GET /ready` chỉ kiểm tra cấu hình, chưa chứng minh key/model/quota hợp lệ.

Danh sách field production và phần Compose cần bổ sung nằm trong
[production-env.md](production-env.md), kèm [mẫu env](../.env.production.example).
Không có file Compose production riêng trong backend. Khi chạy trên server,
cấu hình địa chỉ AI trong stack đang sử dụng; nếu chạy container, dùng hostname
service AI trên network chung. `localhost` trong container là chính container đó.

## Kiểm thử

```sh
# Backend: client HTTP, contract và kiểm tra giới hạn dự đoán; không cần database.
./gradlew test --tests '*AiServiceClientTest' --tests '*AiWireContractTest'
# Backend: ownership, phân tích không lưu, confirm thay thế/rollback; cần DB test riêng.
./gradlew test --tests '*MealAnalysisIntegrationTest'
# AI: contract FastAPI, xử lý provider và schema; provider được mock.
python -m pytest
```

Các fixture JSON của backend và AI giống nhau. Test mock không gửi request
OpenAI trả phí; để kiểm tra model thật, khởi động cả hai service với cấu hình
thật rồi thực hiện luồng upload → analyze → confirm bằng tài khoản test.
