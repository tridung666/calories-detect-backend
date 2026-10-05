# Avatar and meal images

Set `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, and `CLOUDINARY_API_SECRET` in the environment (or local `.env`). The application binds these to `app.cloudinary`. Flyway V8 adds nullable image columns to users and meals; existing rows need no backfill.

All image endpoints require the existing Bearer access token:

| Endpoint | Request | Success response data |
| --- | --- | --- |
| `PUT /api/users/me/avatar` | Multipart `file` | User including `avatarUrl` |
| `DELETE /api/users/me/avatar` | No body | User with `avatarUrl: null` |
| `PUT /api/meals/{mealId}/image` | Multipart `file` | Meal including `imageUrl` |
| `DELETE /api/meals/{mealId}/image` | No body | Meal with `imageUrl: null` |

```sh
curl -X PUT "$API_URL/api/users/me/avatar" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -F 'file=@avatar.png;type=image/png'
```

Uploads accept JPEG, PNG and WebP up to 10 MiB (10,485,760 bytes), including phone photos above the previous 5 MiB limit. The service checks MIME type and file signatures; Cloudinary decodes the upload and restricts its format. The servlet also limits individual files to 10 MiB and the entire multipart request to 11 MiB. Tomcat drains up to 16 MiB of rejected request data so clients can receive the JSON 413 response. Meal uploads request a unique public ID in the form `calories-detect/meals/{userId}/{mealId}/{uuid}`, using IDs from the meal after ownership verification. Avatar uploads request `calories-detect/avatars/{uuid}`. Neither path uses the original filename, and uploads keep `overwrite=false`. Only the secure URL is exposed in responses; the exact public ID returned by Cloudinary is persisted and reused for replacement, deletion, and rollback cleanup. Existing user and meal read/list responses also include the URL. A meal has one image; meal items have none.

Clients should use the same 10 MiB limit for validation. Larger phone photos need resizing or compression before upload, and HEIC/HEIF photos need conversion to a supported format. This endpoint does not compress or convert images. Cloudinary account limits still apply; check the account's upload limits when changing the backend limit. Reverse proxies must also allow the 11 MiB multipart request.

Uploads explicitly set `asset_folder` for Cloudinary dynamic folder mode: avatars go into `calories-detect/avatars`, and meal images into `calories-detect/meals/{userId}/{mealId}`. A slash-separated `public_id` alone does not create Media Library folders in this mode. This applies to new uploads only; existing assets remain in their current folders and can still be replaced or deleted using their stored public IDs. See [Cloudinary folder modes](https://cloudinary.com/documentation/folder_modes).

Errors use `BaseResponse`: invalid/empty/missing files return HTTP 400 with code 15000; oversized uploads return HTTP 413/code 15001; storage upload and deletion failures return HTTP 503/codes 15002 and 15003. Non-owned or missing meals return the existing HTTP 404/code 14000 before storage is called. Deleting an absent image succeeds.

Image mutations lock the owning database row. Replacements upload first, flush the new database fields, then delete the old asset with CDN invalidation. A storage deletion failure rolls back the database update; rollback triggers a best-effort deletion of the new upload. Database flush failures also remove the new upload without deleting the old image. Cleanup failures are logged with the public ID for manual reconciliation.

PostgreSQL and Cloudinary cannot share an atomic transaction. A process crash, ambiguous provider timeout, or database commit failure after remote deletion can still require reconciliation. This implementation does not include a durable cleanup queue. The existing meal deletion endpoint also removes its Cloudinary image. Account deletion and durable cleanup jobs are outside this change.

Tests mock Cloudinary's SDK uploader and run endpoint, security, transaction, concurrency, and multipart-limit checks against the project's PostgreSQL test database. No live Cloudinary calls or AI functionality are used.
