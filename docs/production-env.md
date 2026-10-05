# Biến môi trường production cho Cloudinary, AI và frontend

Mẫu đầy đủ: [`.env.production.example`](../.env.production.example). Bổ sung vào
`.env` hiện có trên VPS, không thay các credential, image tag, reverse proxy hay
volume database đang dùng. File `.env` của Compose dùng để nội suy cấu hình;
biến chỉ tới container khi được khai báo trong `environment` hoặc `env_file`.

## Biến mới bắt buộc

| Service | Field | Cách cấu hình |
| --- | --- | --- |
| Backend | `CLOUDINARY_CLOUD_NAME` | Cloud name trên Cloudinary |
| Backend | `CLOUDINARY_API_KEY` | API key của cùng tài khoản |
| Backend | `CLOUDINARY_API_SECRET` | API secret của cùng tài khoản |
| Backend | `AI_SERVICE_BASE_URL` | `http://ai:8000` khi hai container cùng network |
| AI | `OPENAI_API_KEY` | Key có quyền gọi model và quota |
| AI | `OPENAI_MODEL` | Model hỗ trợ ảnh và strict structured outputs |
| Compose | `AI_TAG` | `latest` để bootstrap, sau đó CD dùng `sha-<full-commit>` |

`AI_SERVICE_BASE_URL` có mặc định localhost dành cho chạy trực tiếp trên máy;
trên VPS chạy container phải cấu hình DNS service. Không publish port AI ra
Internet: frontend chỉ gọi Spring. Spring chỉ gửi URL ảnh tới AI; OpenAI tải
ảnh Cloudinary qua URL công khai.

## Biến timeout có mặc định

```dotenv
AI_SERVICE_CONNECT_TIMEOUT=3s
AI_SERVICE_RESPONSE_TIMEOUT=75s
OPENAI_TIMEOUT_SECONDS=60
```

Thứ tự timeout: OpenAI 60s < Spring 75s < frontend analysis 90s. Reverse proxy
phải cho phép request analysis chờ ít nhất 90s (ví dụ Nginx
`proxy_read_timeout 100s`). Nếu tăng timeout AI, tăng cả timeout Spring,
frontend và proxy tương ứng. Các request frontend khác giữ timeout hiện có.

## Các field hiện có vẫn cần

Backend cần `SPRING_PROFILES_ACTIVE=prod`, cấu hình DB (`DB_URL` hoặc
`DB_HOST`, `DB_PORT`, `DB_NAME`; kèm `DB_USERNAME`, `DB_PASSWORD`),
`JWT_SECRET` ít nhất 32 byte, `GOOGLE_CLIENT_ID`, `MAIL_USERNAME`,
`MAIL_PASSWORD`. `JWT_EXPIRATION=15m` và `JWT_REFRESH_EXPIRATION=7d` có mặc định.
Profile prod luôn dùng Secure cookie; `AUTH_ALLOWED_ORIGINS` mặc định
`https://caloriesdetect.com`, cần đặt nếu domain thực tế khác.
Không cần thêm biến môi trường cho trang settings, avatar hay confirm-analysis.

Frontend không cần Cloudinary/OpenAI key hoặc URL AI. Khi build dùng
`VITE_API_BASE_URL=/api` và `VITE_GOOGLE_CLIENT_ID` trùng Google client ID ở
backend. `API_PROXY_TARGET` chỉ dùng Vite dev. Biến `VITE_*` được nhúng lúc build,
đổi `.env` của container frontend đang chạy không đổi bundle đã build.

## Bổ sung vào Compose đang chạy trên VPS

AI CD hiện thao tác `/opt/calories-detect/docker-compose.yml`. Thêm service
`ai` theo [hướng dẫn AI](https://github.com/tridung666/calories-detect-AI/blob/main/docs/ai-service.md)
và khai báo trên service `backend`:

```yaml
services:
  backend:
    environment:
      CLOUDINARY_CLOUD_NAME: ${CLOUDINARY_CLOUD_NAME:?Set CLOUDINARY_CLOUD_NAME}
      CLOUDINARY_API_KEY: ${CLOUDINARY_API_KEY:?Set CLOUDINARY_API_KEY}
      CLOUDINARY_API_SECRET: ${CLOUDINARY_API_SECRET:?Set CLOUDINARY_API_SECRET}
      AI_SERVICE_BASE_URL: http://ai:8000
      AI_SERVICE_CONNECT_TIMEOUT: ${AI_SERVICE_CONNECT_TIMEOUT:-3s}
      AI_SERVICE_RESPONSE_TIMEOUT: ${AI_SERVICE_RESPONSE_TIMEOUT:-75s}
```

Đây là phần bổ sung; giữ các cấu hình DB/auth/SMTP/frontend đang có. Gắn AI
vào cùng network với backend. Khai báo OpenAI fields chỉ trong `ai.environment`;
không đưa `.env` dùng chung chứa OpenAI key vào `backend.env_file`. Healthcheck
AI `/ready` chỉ kiểm tra key/model có giá trị, chưa kiểm chứng quyền, quota hay
chất lượng dự đoán.

Chạy `docker compose config --quiet`, khởi động AI rồi recreate backend để nhận
biến mới. Flyway tự chạy V8 (ảnh), V9 (dinh dưỡng decimal), V10 (bỏ
`normalized_name`); backup DB trước khi rollout migration. Khi confirm, toàn bộ
món cũ được thay bằng các món đã review trong một transaction.

## GitHub Actions, không phải application .env

Các repo CD cần `VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY`; AI và frontend dùng
`VPS_PORT` (AI mặc định 22). AI hỗ trợ `VPS_SSH_KNOWN_HOSTS` và biến
`VPS_DEPLOY_PATH` (mặc định `/opt/calories-detect`). Frontend CD cần secret
`VITE_GOOGLE_CLIENT_ID`. GHCR dùng `GITHUB_TOKEN` tự cấp trong workflow.
Backend và AI cùng dùng `.deploy.lock` để tránh ghi đè `.env` khi deploy.
Tạo PR không deploy production; CD chạy sau CI thành công trên main.
