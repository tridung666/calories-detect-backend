# Giải thích các thay đổi Cloudinary

Tài liệu mô tả phần thay đổi upload ảnh trong working tree tại ngày 29/09/2026: 11 file mới và 19 file đã sửa, chưa tính chính tài liệu này. Tại thời điểm viết, các thay đổi chưa được stage. Nội dung dưới đây giải thích implementation hiện có, không coi các cải tiến được đề xuất là chức năng đã hoàn thành.

Cập nhật 06/10/2026: giới hạn upload hiện tại đã tăng lên 10 MiB/file, 11 MiB/request và `max-swallow-size` là 16 MiB để nhận ảnh từ điện thoại lớn hơn 5 MiB. Các mô tả 5 MiB bên dưới ghi lại implementation cũ; xem [image-uploads.md](image-uploads.md) để biết hợp đồng API hiện tại.

## 1. Chức năng được bổ sung

Người dùng có thể upload, thay thế và xóa avatar của mình; upload, thay thế và xóa một ảnh cho mỗi bữa ăn thuộc tài khoản của mình. Khi xóa bữa ăn, hệ thống cũng xóa ảnh tương ứng trên Cloudinary. `meal_items` không có ảnh riêng trong thay đổi này.

Cloudinary giữ nội dung file. PostgreSQL chỉ giữ hai thông tin:

| Giá trị | Mục đích |
| --- | --- |
| `publicId` | Định danh tài nguyên trên Cloudinary để backend xóa ảnh hoặc dọn ảnh khi thao tác thất bại |
| URL HTTPS | Đường dẫn để client hiển thị ảnh |

Response user/meal chỉ bổ sung URL ảnh; không trả trường public ID. Ảnh bữa ăn dùng public ID được yêu cầu dạng `calories-detect/meals/<userId>/<mealId>/<UUID>`, avatar dùng `calories-detect/avatars/<UUID>`. ID user và meal lấy từ bữa ăn đã kiểm tra quyền sở hữu; UUID tạo mới mỗi lần upload. Database lưu chính xác `public_id` Cloudinary trả về, không dựng lại từ đường dẫn khi xóa ảnh. Tên file người dùng không được dùng làm mã lưu trữ.

## 2. API và dữ liệu cần gửi

| Method | Endpoint | Body | Kết quả thành công trong `data` |
| --- | --- | --- | --- |
| `PUT` | `/api/users/me/avatar` | Multipart, trường `file` | `UserResponse` có `avatarUrl` |
| `DELETE` | `/api/users/me/avatar` | Không có | `UserResponse` có `avatarUrl: null` |
| `PUT` | `/api/meals/{mealId}/image` | Multipart, trường `file` | `MealResponse` có `imageUrl` |
| `DELETE` | `/api/meals/{mealId}/image` | Không có | `MealResponse` có `imageUrl: null` |

Các API trả `BaseResponse`, thành công là HTTP 200. Upload vào đối tượng đã có ảnh sẽ thay ảnh. Xóa ảnh khi không có ảnh vẫn thành công. Xóa ảnh bữa ăn không xóa bữa ăn.

Các endpoint yêu cầu `Authorization: Bearer <access_token>`. Avatar lấy user từ thông tin xác thực; không nhận `userId` từ client. Endpoint ảnh bữa ăn kiểm tra cả `mealId` lẫn người sở hữu trước khi gọi storage.

Theo [SecurityConfig.java](../src/main/java/com/tridung/caloriesdetect/config/SecurityConfig.java), bốn API ảnh không yêu cầu CSRF. Cấu hình CSRF hiện có áp dụng cho các request cần bảo vệ ở `/api/auth/login`, `/api/auth/google`, `/api/auth/refresh-token`, `/api/auth/logout`; đây là cấu hình được tái sử dụng, không phải thay đổi Cloudinary.

## 3. Cách các tầng phối hợp

```text
Client gửi multipart + Bearer token
    → Spring Security xác thực
    → UserAvatarController / MealImageController
    → UserServiceImpl / MealServiceImpl mở transaction, lấy và khóa bản ghi
    → ImageUpdateService phối hợp lưu ảnh và cập nhật database
    → ImageStorageService (interface)
    → CloudinaryImageStorageService kiểm tra file và gọi Cloudinary SDK
    → Callback cập nhật entity, saveAndFlush
    → Mapper tạo UserResponse / MealResponse
    → BaseResponse trả kết quả cho client
```

Controller biết HTTP, service nghiệp vụ biết user/meal, còn storage biết cách nói chuyện với Cloudinary. `ImageUpdateService` chứa cơ chế dùng chung để hai nghiệp vụ không phải lặp lại xử lý thay ảnh và rollback.

## 4. Từng file mới và từng hàm mới

### 4.1. CloudinaryProperties.java

File: [CloudinaryProperties.java](../src/main/java/com/tridung/caloriesdetect/config/CloudinaryProperties.java).

Record ánh xạ cấu hình có prefix `app.cloudinary` thành `cloudName`, `apiKey`, `apiSecret`. `@Validated` và `@NotBlank` yêu cầu các giá trị không trống; validation này không xác minh credentials có được Cloudinary chấp nhận hay không.

Hàm `toString()` được override để luôn trả `CloudinaryProperties[credentials=REDACTED]`, tránh lộ credentials khi in object cấu hình. Các accessor của record do Java sinh tự động.

### 4.2. CloudinaryConfig.java

File: [CloudinaryConfig.java](../src/main/java/com/tridung/caloriesdetect/config/CloudinaryConfig.java).

`@EnableConfigurationProperties(CloudinaryProperties.class)` đăng ký cấu hình. Hàm `cloudinary(CloudinaryProperties properties)` tạo bean Cloudinary SDK từ `cloud_name`, `api_key`, `api_secret` và đặt `secure=true`. Service nhận client này qua constructor injection.

### 4.3. UserAvatarController.java

File: [UserAvatarController.java](../src/main/java/com/tridung/caloriesdetect/controller/UserAvatarController.java).

Controller có đường dẫn gốc `/api/users/me/avatar`.

| Hàm | Tác dụng |
| --- | --- |
| `uploadAvatar(MultipartFile file)` | Nhận `PUT` với `multipart/form-data`, lấy phần `@RequestPart("file")`, gọi `UserService.uploadAvatar(file)` và bọc kết quả bằng `BaseResponse.success(...)` |
| `deleteAvatar()` | Nhận `DELETE`, gọi `UserService.deleteAvatar()` và trả user sau khi xóa ảnh |

Controller không trực tiếp kiểm tra chữ ký file, truy cập database hay gọi Cloudinary.

### 4.4. MealImageController.java

File: [MealImageController.java](../src/main/java/com/tridung/caloriesdetect/controller/MealImageController.java).

Controller có đường dẫn gốc `/api/meals/{mealId}/image`.

| Hàm | Tác dụng |
| --- | --- |
| `uploadImage(Long mealId, MultipartFile file)` | Nhận ID từ URL và file từ multipart, gọi `MealService.uploadImage(...)`, trả `BaseResponse<MealResponse>` |
| `deleteImage(Long mealId)` | Gọi `MealService.deleteImage(...)`, trả bữa ăn sau khi bỏ thông tin ảnh |

Kiểm tra quyền sở hữu nằm trong service, không dựa vào ID do client gửi để tự cho phép thao tác.

### 4.5. ImageStorageService.java

File: [ImageStorageService.java](../src/main/java/com/tridung/caloriesdetect/service/ImageStorageService.java).

Interface định nghĩa hợp đồng lưu trữ độc lập với Cloudinary SDK.

| Thành phần | Tác dụng |
| --- | --- |
| `StoredImage(String publicId, String secureUrl)` | Record trả hai thông tin của ảnh upload thành công; accessor `publicId()` và `secureUrl()` do Java sinh |
| `upload(MultipartFile file)` | Upload avatar theo đường dẫn hiện có và trả `StoredImage` |
| `upload(MultipartFile file, long userId, long mealId)` | Upload ảnh bữa ăn theo đường dẫn chứa ID user và meal; trả `StoredImage` |
| `delete(String publicId)` | Xóa ảnh theo định danh |

Nhờ interface này, `ImageUpdateService` không phụ thuộc vào API riêng của Cloudinary. Nếu thay nhà cung cấp, có thể triển khai interface bằng một storage khác.

### 4.6. CloudinaryImageStorageService.java

File: [CloudinaryImageStorageService.java](../src/main/java/com/tridung/caloriesdetect/service/impl/CloudinaryImageStorageService.java).

Đây là implementation của `ImageStorageService`. `MAX_IMAGE_SIZE` bằng `5 * 1024 * 1024` byte, tức 5 MiB. `PNG_SIGNATURE` chứa 8 byte nhận dạng PNG.

**`upload(MultipartFile file)`** chọn folder `calories-detect/avatars` cho avatar. Overload **`upload(MultipartFile file, long userId, long mealId)`** chọn folder `calories-detect/meals/<userId>/<mealId>` cho ảnh bữa ăn. Cả hai gọi chung hàm private **`upload(MultipartFile file, String assetFolder)`**, giữ nguyên validation và xử lý lỗi:

1. Từ chối file `null` hoặc rỗng bằng `INVALID_IMAGE`.
2. Kiểm tra kích thước khai báo; quá 5 MiB trả `IMAGE_TOO_LARGE`.
3. Đọc tối đa `MAX_IMAGE_SIZE + 1` byte bằng `readNBytes(...)`, rồi kiểm tra lại số byte thực đọc. Cách này giới hạn lượng dữ liệu đọc ngay cả khi `getSize()` báo sai. Lỗi đọc stream chuyển thành `IMAGE_UPLOAD_FAILED`.
4. Gọi `matchesImageType(...)` để so MIME với chữ ký nội dung file.
5. Sinh `publicId` bằng `assetFolder` + `/` + UUID mới.
6. Gọi SDK upload với `public_id` đã sinh, `asset_folder` tương ứng, `resource_type=image`, `overwrite=false`, `allowed_formats=[jpg, png, webp]`.
7. Kiểm tra response: `public_id` phải là chuỗi không trống; `secure_url` phải là chuỗi bắt đầu bằng `https://`. Kết quả hợp lệ được trả về dưới dạng `StoredImage`.

Nếu SDK ném lỗi hoặc response không hợp lệ, hàm log exception gốc rồi thử `delete(publicId)`. Việc thử dọn ảnh là cần thiết vì server Cloudinary có thể đã tạo ảnh trước khi client gặp timeout hoặc nhận response lỗi. Nếu cleanup tiếp tục thất bại, lỗi phụ được gắn bằng `addSuppressed(...)` và được log; lỗi trả về vẫn là `IMAGE_UPLOAD_FAILED` với nguyên nhân gốc.

Log `Cloudinary upload failed for ...` chứa exception gốc; log `Unable to clean up failed image upload ...` chứa exception cleanup. Lỗi đọc stream xảy ra trước khối gọi SDK nên không đi qua hai log này.

**`delete(String publicId)`** thực hiện:

- Bỏ qua mã `null` hoặc trống, không gọi Cloudinary.
- Gọi `destroy(publicId, ...)` với `resource_type=image`, `invalidate=true` để yêu cầu vô hiệu hóa cache CDN.
- Chấp nhận response `result=ok` hoặc `result=not found` là thành công. Đây là tính idempotent: xóa lại ảnh đã mất vẫn thành công.
- Response khác, lỗi I/O hoặc lỗi runtime được chuyển thành `IMAGE_DELETE_FAILED` và giữ nguyên nhân trong `AppException`.

**`matchesImageType(byte[] bytes, String contentType)`** chỉ chấp nhận các MIME sau:

| MIME | Nội dung cần khớp |
| --- | --- |
| `image/jpeg` | Ít nhất 3 byte, bắt đầu `FF D8 FF` |
| `image/png` | Khớp chữ ký PNG 8 byte |
| `image/webp` | Ít nhất 16 byte, có `RIFF`, `WEBP` và marker `VP8 `, `VP8L` hoặc `VP8X` |

MIME bị thiếu, `image/jpg`, `application/octet-stream`, SVG, GIF hoặc HEIC đều không được chấp nhận bởi hàm hiện tại. Tên file không quyết định định dạng; đổi đuôi HEIC sang `.jpg` không chuyển đổi nội dung ảnh. Hàm chỉ kiểm tra chữ ký, không giải mã toàn bộ ảnh tại backend; Cloudinary còn xử lý nội dung upload và giới hạn định dạng theo options.

### 4.7. ImageUpdateService.java

File: [ImageUpdateService.java](../src/main/java/com/tridung/caloriesdetect/service/ImageUpdateService.java).

Các hàm public dùng `@Transactional(propagation = Propagation.MANDATORY)`: khi được gọi qua Spring proxy, phải có transaction từ bên gọi. Service này không tự mở một transaction độc lập. Khóa bản ghi được lấy ở service nghiệp vụ trước khi gọi vào đây.

**`replace(MultipartFile file, String previousPublicId, Consumer<StoredImage> saveAndFlush)`** upload avatar qua `storage.upload(file)`. Overload **`replace(MultipartFile file, long userId, long mealId, String previousPublicId, Consumer<StoredImage> saveAndFlush)`** upload ảnh bữa ăn qua `storage.upload(file, userId, mealId)`.

Cả hai chuyển kết quả vào hàm private **`replaceUploadedImage(StoredImage uploaded, String previousPublicId, Consumer<StoredImage> saveAndFlush)`** để dùng chung các bước sau:

1. Đăng ký `TransactionSynchronization` để xử lý khi transaction hoàn tất.
2. Gọi `saveAndFlush.accept(uploaded)` để bên gọi lưu public ID và URL Cloudinary trả về vào user hoặc meal.
3. Xóa ảnh cũ qua `storage.delete(previousPublicId)`.

`Consumer<StoredImage>` là callback nhận kết quả upload. `ImageUpdateService` không cần biết entity nào đang được sửa; callback do `UserServiceImpl` hoặc `MealServiceImpl` cung cấp.

**`afterCompletion(int status)`**, hàm override bên trong callback transaction:

- Nếu trạng thái bằng `STATUS_ROLLED_BACK`, thử xóa ảnh mới vừa upload.
- Nếu xóa ảnh mới thất bại, ghi log public ID để đối soát sau; không thay lỗi ban đầu bằng lỗi cleanup.
- Không dọn ảnh khi transaction commit thành công. Nhánh hiện tại chỉ xử lý trạng thái rollback xác định, không xử lý riêng trạng thái transaction không rõ kết quả.

**`delete(String publicId, Runnable clearAndFlush)`**:

1. Chạy callback `clearAndFlush.run()` để xóa thông tin ảnh trong DB, hoặc xóa cả bữa ăn.
2. Gọi `storage.delete(publicId)`.

`Runnable` không nhận đối số và không trả kết quả. Nếu storage ném runtime exception, transaction của bên gọi rollback các thay đổi database.

### 4.8. V8__add_user_and_meal_images.sql

File: [V8__add_user_and_meal_images.sql](../src/main/resources/db/migration/V8__add_user_and_meal_images.sql).

Migration Flyway thêm:

| Bảng | Cột | Kiểu |
| --- | --- | --- |
| `users` | `avatar_public_id` | `VARCHAR(255)` |
| `users` | `avatar_url` | `TEXT` |
| `meals` | `image_public_id` | `VARCHAR(255)` |
| `meals` | `image_url` | `TEXT` |

Các cột cho phép `NULL`; dữ liệu cũ được giữ nguyên và không cần backfill ảnh. File SQL không có hàm Java mới.

### 4.9. docs/image-uploads.md

File: [image-uploads.md](image-uploads.md).

Tài liệu sử dụng bằng tiếng Anh: cấu hình, endpoint, curl, mã lỗi, rollback, concurrency và giới hạn của hệ thống. File này không có hàm mới. Có một mô tả về giới hạn multipart chưa khớp cấu hình hiện tại; xem mục 8 bên dưới.

### 4.10. Hai file test mới

[CloudinaryImageStorageServiceTest.java](../src/test/java/com/tridung/caloriesdetect/service/impl/CloudinaryImageStorageServiceTest.java) kiểm tra riêng storage bằng SDK mock.

[ImageIntegrationTest.java](../src/test/java/com/tridung/caloriesdetect/controller/ImageIntegrationTest.java) kiểm tra endpoint, xác thực, database, rollback và concurrency với Spring context, PostgreSQL và Cloudinary mock. Chi tiết từng hàm ở mục 7.

## 5. Từng file đã sửa và các hàm liên quan

### 5.1. Cấu hình và dependency

| File | Thay đổi | Tác dụng |
| --- | --- | --- |
| [build.gradle](../build.gradle) | Thêm `com.cloudinary:cloudinary-http5:2.4.0` | Cung cấp SDK upload/destroy và HTTP client implementation |
| [.env.example](../.env.example) | Thêm `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET` | Ghi mẫu biến môi trường cần cấu hình |
| [application.yml](../src/main/resources/application.yml) | Thêm `app.cloudinary.cloud-name`, `api-key`, `api-secret` | Đọc ba biến môi trường vào cấu hình ứng dụng |
| [ci.yml](../.github/workflows/ci.yml) | Thêm ba giá trị Cloudinary giả cho CI | Cho phép context bind cấu hình khi chạy test; các test ảnh mock Cloudinary, không dùng chúng để upload thật |

`spring.config.import: optional:file:.env[.properties]` là cơ chế có sẵn để đọc `.env`. Sau khi thay credentials hoặc cloud name, cần khởi động lại ứng dụng để bean nhận cấu hình mới.

### 5.2. Entity, DTO và mapper

| File | Thay đổi và tác dụng |
| --- | --- |
| [User.java](../src/main/java/com/tridung/caloriesdetect/entity/User.java) | Thêm `avatarPublicId`, `avatarUrl`, ánh xạ vào cột migration V8 |
| [Meal.java](../src/main/java/com/tridung/caloriesdetect/entity/Meal.java) | Thêm `imagePublicId`, `imageUrl`, ánh xạ vào cột migration V8 |
| [UserResponse.java](../src/main/java/com/tridung/caloriesdetect/dto/response/auth/UserResponse.java) | Thêm `avatarUrl` để API dùng DTO này trả URL avatar |
| [MealResponse.java](../src/main/java/com/tridung/caloriesdetect/dto/response/meal/MealResponse.java) | Thêm `imageUrl` để API dùng DTO này trả URL ảnh |
| [UserMapper.java](../src/main/java/com/tridung/caloriesdetect/mapper/UserMapper.java) | Bỏ qua hai trường avatar khi tạo entity từ request đăng ký hoặc request admin; ảnh được quản lý qua API riêng |
| [MealMapper.java](../src/main/java/com/tridung/caloriesdetect/mapper/MealMapper.java) | Bỏ qua hai trường ảnh trong `toEntity(MealRequest, User)` |

MapStruct tự map trường URL cùng tên từ entity sang response. Việc thêm `ignore=true` khi tạo entity cũng đáp ứng `unmappedTargetPolicy=ERROR`: các field mới phải được khai báo cách xử lý rõ ràng. Các mapper không thêm hàm viết tay mới. Getter/setter của entity và accessor của DTO record được sinh tự động.

### 5.3. MealRepository.java

File: [MealRepository.java](../src/main/java/com/tridung/caloriesdetect/repository/MealRepository.java).

Hàm mới `findOwnedByIdForUpdate(Long id, Long userId)` dùng JPQL lọc đồng thời `m.id` và `m.userId.id`, trả `Optional<Meal>`. `@Lock(PESSIMISTIC_WRITE)` yêu cầu khóa ghi bản ghi trong transaction để các thao tác cạnh tranh trên cùng bữa ăn được phối hợp qua database.

### 5.4. UserService.java và UserServiceImpl.java

File: [UserService.java](../src/main/java/com/tridung/caloriesdetect/service/UserService.java), [UserServiceImpl.java](../src/main/java/com/tridung/caloriesdetect/service/impl/UserServiceImpl.java).

Interface thêm khai báo `uploadAvatar(MultipartFile file)` và `deleteAvatar()`, đều trả `UserResponse`. Implementation nhận thêm `CurrentUserProvider` và `ImageUpdateService` qua constructor do Lombok sinh.

| Hàm mới trong implementation | Tác dụng |
| --- | --- |
| `uploadAvatar(MultipartFile file)` | Mở transaction, khóa user hiện tại, gọi `replace(...)`; callback gán public ID/URL mới và `userRepository.saveAndFlush(user)`; trả DTO |
| `deleteAvatar()` | Mở transaction, khóa user, gọi `delete(...)`; callback đặt hai trường avatar thành `null` rồi `saveAndFlush`; trả DTO |
| `findCurrentUserForUpdate()` | Lấy ID từ `CurrentUserProvider`, tìm user bằng `userRepository.findByIdForUpdate(...)`; không có user thì ném `USER_NOT_FOUND` |

`UserRepository.findByIdForUpdate(...)` đã tồn tại và được tái sử dụng, không phải hàm mới của đợt thay đổi này.

### 5.5. MealService.java và MealServiceImpl.java

File: [MealService.java](../src/main/java/com/tridung/caloriesdetect/service/MealService.java), [MealServiceImpl.java](../src/main/java/com/tridung/caloriesdetect/service/impl/MealServiceImpl.java).

Interface thêm `uploadImage(Long mealId, MultipartFile file)` và `deleteImage(Long mealId)`, đều trả `MealResponse`. Implementation nhận thêm `ImageUpdateService`.

| Hàm mới trong implementation | Tác dụng |
| --- | --- |
| `uploadImage(Long mealId, MultipartFile file)` | Mở transaction, kiểm tra sở hữu và khóa bữa ăn, truyền `meal.getUserId().getId()` và `meal.getId()` vào overload thay ảnh; callback gán chính xác public ID/URL được trả về rồi `saveAndFlush`; trả DTO |
| `deleteImage(Long mealId)` | Mở transaction, khóa bữa ăn của user; callback xóa hai field ảnh, `saveAndFlush`, phối hợp xóa storage; trả DTO |
| `findOwnedMealForUpdate(Long mealId)` | Gọi repository với ID bữa ăn và ID user hiện tại; không tìm thấy hoặc không sở hữu đều ném `MEAL_NOT_FOUND` |

Hai hàm cũ được thay đổi:

- `updateMeal(...)`: chuyển sang `findOwnedMealForUpdate(...)` để phối hợp với upload/xóa ảnh đồng thời; vẫn chỉ sửa loại và ngày bữa ăn.
- `deleteMeal(...)`: khóa bữa ăn, gọi `imageUpdateService.delete(...)`. Callback xóa meal items, xóa bữa ăn rồi `flush()`. Sau đó storage xóa ảnh; nếu bước này lỗi thì transaction DB rollback.

Các API bữa ăn cũ dùng `/api/meal/...`, còn API ảnh mới dùng `/api/meals/.../image`; cần chú ý khác biệt số ít/số nhiều khi gửi request.

### 5.6. AppException.java, ErrorCode.java và GlobalExceptionHandler.java

File: [AppException.java](../src/main/java/com/tridung/caloriesdetect/exception/AppException.java), [ErrorCode.java](../src/main/java/com/tridung/caloriesdetect/exception/ErrorCode.java), [GlobalExceptionHandler.java](../src/main/java/com/tridung/caloriesdetect/exception/GlobalExceptionHandler.java).

Constructor mới `AppException(ErrorCode errorCode, Throwable cause)` gọi `super(errorCode.getMessage(), cause)`, giúp giữ nguyên nhân SDK/I/O phía trong khi trả thông báo nghiệp vụ phía ngoài.

| ErrorCode mới | Mã ứng dụng | HTTP | Khi xảy ra |
| --- | --- | --- | --- |
| `INVALID_IMAGE` | `15000` | 400 | Thiếu/rỗng file, MIME không được hỗ trợ hoặc chữ ký không khớp |
| `IMAGE_TOO_LARGE` | `15001` | 413 | File vượt giới hạn service hoặc servlet báo vượt giới hạn multipart |
| `IMAGE_UPLOAD_FAILED` | `15002` | 503 | Lỗi đọc file, SDK upload lỗi hoặc response upload không hợp lệ |
| `IMAGE_DELETE_FAILED` | `15003` | 503 | SDK xóa ảnh lỗi hoặc response xóa không được chấp nhận |

Các hàm mới trong global handler:

- `handleMaxUploadSizeExceededException()`: chuyển exception giới hạn multipart thành `IMAGE_TOO_LARGE`, rồi dùng `handleAppException(...)` để tạo response thống nhất.
- `handleMissingImagePart()`: chuyển `MissingServletRequestPartException` thành `INVALID_IMAGE`. Handler áp dụng toàn cục cho loại exception này, không chỉ riêng hai controller ảnh.

Hàm cũ `handleAppException(...)` thêm mapping lỗi upload/delete sang 503, file quá lớn sang 413. `INVALID_IMAGE` đi qua nhánh mặc định 400. `MEAL_NOT_FOUND` vẫn là 404/code `14000`.

### 5.7. AuthProvidersIntegrationTest.java

File: [AuthProvidersIntegrationTest.java](../src/test/java/com/tridung/caloriesdetect/service/AuthProvidersIntegrationTest.java).

Bài test migration auth cũ đặt `.target("7")` cho bước nâng cấp thay vì chạy tới migration mới nhất. Như vậy khi V8 được thêm, bài test này vẫn kiểm tra đúng phạm vi V5 → V7. Không thêm hàm mới trong file này.

## 6. Transaction, rollback và xử lý đồng thời

Luồng thay ảnh thành công:

```text
BEGIN transaction + khóa user/meal
  → Upload ảnh mới
  → Đăng ký cleanup nếu rollback
  → Gán public ID + URL mới, saveAndFlush
  → Xóa ảnh cũ trên Cloudinary
COMMIT transaction
```

`saveAndFlush()` đẩy câu lệnh SQL xuống database để phát hiện lỗi sớm; flush chưa phải commit. Nếu database từ chối ID/URL mới, code chưa đi tới bước xóa ảnh cũ.

| Điểm thất bại | Hành vi hiện tại |
| --- | --- |
| Upload thất bại | Chưa thay thông tin ảnh trong DB; storage thử dọn public ID vừa sinh nếu lỗi nằm trong khối gọi SDK |
| Lưu/flush DB thất bại sau upload | Transaction rollback; callback thử xóa ảnh mới; không gọi xóa ảnh cũ |
| Xóa ảnh cũ báo lỗi khi thay ảnh | Rollback DB về giá trị cũ và thử xóa ảnh mới; trạng thái ảnh cũ trên Cloudinary có thể không xác định nếu lỗi là timeout |
| Xóa ảnh hiện tại báo lỗi | Rollback thao tác clear field hoặc xóa meal trong DB |
| Cleanup sau rollback thất bại | Ghi log public ID; có thể còn ảnh thừa cần dọn sau |

Khóa ghi giúp hai request thay ảnh cùng user/meal lần lượt xử lý: request sau đọc public ID đã được request trước commit, rồi xóa đúng ảnh trước đó. Khóa được giữ trong transaction bao gồm thời gian gọi Cloudinary, nên provider chậm cũng khiến request cùng bản ghi chờ lâu hơn.

PostgreSQL và Cloudinary không có transaction nguyên tử chung. Nếu ảnh cũ đã bị xóa nhưng commit DB thất bại, hoặc process dừng giữa các bước, dữ liệu có thể lệch nhau. Hiện chưa có durable cleanup queue/outbox hay job đối soát. Xóa tài khoản kèm dọn avatar cũng không được bổ sung trong thay đổi này.

## 7. Từng hàm trong các file test mới

### 7.1. CloudinaryImageStorageServiceTest.java

Unit test dùng mock `Cloudinary` và `Uploader`; không upload ảnh thật.

| Hàm | Điều kiểm tra |
| --- | --- |
| `acceptsSupportedImageTypes(String type)` | JPEG, PNG, WebP hợp lệ được chuyển tới uploader và trả đúng public ID/URL |
| `acceptsMaximumSize()` | Chấp nhận đúng 5 MiB ở tầng service |
| `boundsStreamEvenWhenReportedSizeIsIncorrect()` | Chặn stream vượt giới hạn dù kích thước khai báo nhỏ |
| `mapsFileReadFailure()` | Lỗi đọc stream chuyển thành `IMAGE_UPLOAD_FAILED`, không gọi uploader |
| `rejectsNullFile()` | File `null` trả `INVALID_IMAGE` |
| `malformedUploadResponseTriggersCleanup(boolean mealImage)` | Kiểm tra cả hai đường upload: response thiếu URL HTTPS hợp lệ dẫn đến lỗi và cleanup đúng public ID đã gửi tới Cloudinary |
| `cleanupFailurePreservesUploadError()` | Cleanup lỗi vẫn giữ mã lỗi upload |
| `mapsUncheckedDeleteFailure()` | Runtime exception từ destroy chuyển thành `IMAGE_DELETE_FAILED` |
| `ignoresAbsentPublicId()` | Public ID `null` hoặc rỗng không gọi uploader |

Hàm hỗ trợ:

| Hàm | Vai trò |
| --- | --- |
| `setup()` | Nối mock Cloudinary với mock uploader trước mỗi test |
| `jpeg()` | Tạo multipart mẫu có chữ ký JPEG; không phải bài kiểm tra giải mã ảnh đầy đủ |
| `assertError(action, code)` | Chạy thao tác và kiểm tra đúng loại `AppException`, đúng `ErrorCode` |

### 7.2. ImageIntegrationTest.java

Test khởi động Spring ở cổng ngẫu nhiên, dùng database PostgreSQL được cấu hình cho ứng dụng và mock Cloudinary/mail. Các test có tham số `avatar` chạy hai lần: `true` cho avatar, `false` cho ảnh bữa ăn.

| Hàm | Điều kiểm tra |
| --- | --- |
| `uploadsPersistsAndReturnsImageInExistingResponses(...)` | Upload lưu ID/URL, ID không dựa trên tên file, API đọc hiện có trả URL, response không có trường public ID |
| `replacesImageWithUniqueIdAndDeletesPreviousAsset(...)` | Upload lần sau tạo ID khác và xóa ảnh trước với CDN invalidation |
| `mealImageUsesExactProviderPublicIdForPersistenceReplacementAndDeletion()` | Cloudinary trả ID khác ID yêu cầu; DB lưu đúng ID trả về, thay ảnh và xóa ảnh đều dùng lại ID đó |
| `deleteClearsBothFieldsAndIsIdempotent(...)` | Xóa sạch ID/URL, xóa lần hai vẫn thành công và không gọi destroy thừa |
| `alreadyMissingCloudinaryAssetCanBeDeleted(...)` | Cloudinary trả `not found` vẫn được xem là xóa thành công |
| `deletingMealAlsoDeletesItsImage()` | Xóa bữa ăn kèm xóa ảnh storage |
| `deletingMealRollsBackWhenImageCannotBeDeleted()` | Storage lỗi thì việc xóa bữa ăn rollback |
| `updatingMealPreservesImage()` | Sửa loại/ngày bữa ăn không làm mất ảnh và không gọi uploader |
| `otherUserCannotUploadOrDeleteMealImage()` | Bữa ăn người khác hoặc không tồn tại trả 404 trước khi gọi storage |
| `endpointsRequireAuthentication(...)` | Thiếu xác thực trả 401 |
| `rejectsInvalidEmptyAndSpoofedFiles(...)` | Từ chối SVG, nội dung giả, MIME sai, file rỗng và thiếu phần `file` |
| `rejectsOversizedImage(...)` | File 5 MiB + 1 byte bị chặn ở tầng service |
| `uploadFailureKeepsPreviousImage(...)` | Upload lỗi giữ nguyên ID/URL cũ, không xóa ảnh cũ |
| `deleteFailureRollsBackDatabaseFields(...)` | Xóa storage lỗi thì DB giữ lại thông tin ảnh |
| `replacementDeleteFailureRollsBackAndCleansUpNewUpload(...)` | Không xóa được ảnh cũ thì rollback và thử dọn ảnh mới |
| `databaseFailureCleansUpUploadWithoutDeletingPreviousAsset(...)` | Public ID quá 255 ký tự gây lỗi DB; ảnh mới được dọn, ảnh cũ không bị xóa |
| `concurrentReplacementsLeaveOneImage(...)` | Hai request upload đồng thời tạo hai ảnh, chỉ xóa ảnh không còn được DB tham chiếu |
| `imageMigrationPreservesExistingUsersAndMeals()` | V7 → V8 giữ user/meal cũ, thêm cột nullable, không thêm cột ảnh vào meal items |
| `servletRejectsOversizedMultipartWithProjectErrorEnvelope()` | Gửi multipart lớn qua HTTP thật để kiểm tra 413/code 15001 từ servlet |

Hàm hỗ trợ:

| Hàm | Vai trò |
| --- | --- |
| `setup()` | Cấu hình mock upload/destroy, tạo user, JWT và bữa ăn mẫu |
| `cleanup()` | Xóa các user được tạo trong test |
| `createUser()` | Tạo user với email UUID, ghi lại ID phục vụ cleanup |
| `token(User user)` | Sinh chuỗi Bearer JWT cho user mẫu |
| `path(boolean avatar)` | Chọn đường dẫn avatar hoặc ảnh bữa ăn |
| `field(boolean avatar)` | Chọn tên trường `avatarUrl` hoặc `imageUrl` |
| `url(String id)` | Tạo URL Cloudinary giả từ public ID |
| `file()` | Tạo multipart PNG với tên có `../../` để kiểm tra ID không phụ thuộc tên file |
| `storedId(boolean avatar)` | Đọc public ID hiện tại từ DB |
| `storedUrl(boolean avatar)` | Đọc URL hiện tại từ DB |
| `seedImage(boolean avatar)` | Gắn ảnh cũ vào entity để test thay/xóa/rollback |
| `upload(boolean avatar)` | Gửi PUT bằng MockMvc, kiểm tra response thành công và không lộ trường public ID |

## 8. Giới hạn multipart cần phân biệt

Service và servlet đều giới hạn file ở 5 MiB; toàn bộ multipart request giới hạn 6 MiB. `application.yml` đã khai báo:

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 5MB
      max-request-size: 6MB
server:
  tomcat:
    max-swallow-size: 8MB
```

Tomcat đọc bỏ tối đa 8 MiB phần body bị từ chối để client nhận được JSON lỗi 413 thay vì bị ngắt kết nối. Request lớn hơn mức này vẫn có thể bị ngắt kết nối. Test HTTP thật kiểm tra file 3 MiB và đúng 5 MiB đi qua servlet tới uploader được mock, cùng request vượt giới hạn trả 413/code 15001. Các test này kiểm tra giới hạn multipart, không kiểm tra khả năng Cloudinary giải mã ảnh.

## 9. Cách cấu hình, test và đọc lỗi

### Cấu hình

```dotenv
CLOUDINARY_CLOUD_NAME=your-cloud-name
CLOUDINARY_API_KEY=your-api-key
CLOUDINARY_API_SECRET=your-api-secret
```

Lấy đúng Cloud name và credentials tương ứng từ Cloudinary Console; không dùng tên hiển thị của project thay cho Cloud name. Không đưa secret hoặc access token thật vào tài liệu/commit.

### Test thủ công

Chạy ứng dụng với PostgreSQL và cấu hình cần thiết:

```bash
./gradlew bootRun
```

Upload avatar với access token còn hạn:

```bash
curl -i --request PUT 'http://localhost:8080/api/users/me/avatar' \
  --header 'Authorization: Bearer YOUR_ACCESS_TOKEN' \
  --form 'file=@/duong-dan/avatar.jpeg;type=image/jpeg'
```

Upload ảnh bữa ăn: thay URL bằng `/api/meals/<mealId>/image` và chọn ảnh tương ứng. Xóa ảnh: dùng `DELETE` cùng URL, giữ Authorization và bỏ `--form`.

Trong Postman: Authorization → Bearer Token → nhập token không có tiền tố `Bearer`; Body → form-data → key `file`, kiểu File; chọn file và MIME `image/jpeg`, `image/png` hoặc `image/webp`. Để Postman tự tạo header `Content-Type` của toàn request kèm boundary. MIME của phần file và Content-Type của toàn request là hai giá trị khác nhau.

Sau upload, kiểm tra `data.avatarUrl`/`data.imageUrl`, mở URL để xem ảnh, rồi gọi API đọc user/bữa ăn để xác nhận URL đã được lưu. Thử thay ảnh và xóa ảnh để kiểm tra vòng đời đầy đủ.

### Test tự động

```bash
./gradlew test --tests 'com.tridung.caloriesdetect.service.impl.CloudinaryImageStorageServiceTest'
./gradlew test --tests 'com.tridung.caloriesdetect.controller.ImageIntegrationTest'
```

Integration test cần database dành cho test và các cấu hình ứng dụng liên quan; không cần chạy `bootRun` riêng. Hai bộ test trên mock Cloudinary, không xác minh credentials thật. Xem kết quả tại `build/reports/tests/test/index.html`. Danh sách test trong tài liệu mô tả nội dung kiểm tra, không khẳng định tất cả test đã pass ở thời điểm đọc.

### Phân biệt các lỗi thường gặp

| Triệu chứng | Cách đọc |
| --- | --- |
| 400/code 15000 | Kiểm tra key `file`, file rỗng, MIME của phần file và định dạng thật; đây là lỗi validation trước upload Cloudinary |
| 413/code 15001 | Kiểm tra cả giới hạn service và multipart servlet |
| 503/code 15002 | Xem exception ở `Cloudinary upload failed for ...`; nếu không có log này thì còn có nhánh lỗi đọc stream trước SDK |
| `Invalid cloud_name ...` trong log SDK | Cloudinary từ chối cloud name được ứng dụng sử dụng; kiểm tra cấu hình và restart |
| `Unable to clean up failed image upload ...` | Đây là lỗi cleanup sau lỗi upload; cần đọc lỗi upload phía trước để biết nguyên nhân ban đầu |
| 503/code 15003 | Thao tác xóa storage thất bại; DB được rollback trong luồng service, nhưng lỗi mạng mơ hồ có thể cần đối soát trạng thái ảnh thực tế |

## Folder trong Cloudinary Media Library

Upload truyền rõ `asset_folder`: avatar vào `calories-detect/avatars`, ảnh bữa ăn vào `calories-detect/meals/{userId}/{mealId}`. `public_id` được tạo bằng folder + `/` + UUID. Trong dynamic folder mode, chỉ đặt dấu `/` trong `public_id` không tạo folder trong Media Library; cần `asset_folder`. Xem [Cloudinary folder modes](https://cloudinary.com/documentation/folder_modes). Thay đổi này áp dụng cho upload mới, không tự chuyển ảnh cũ. Việc thay/xóa ảnh cũ vẫn dùng public ID đã lưu trong DB.
