package com.tridung.caloriesdetect.service.impl;

import com.cloudinary.Cloudinary;
import com.tridung.caloriesdetect.exception.AppException;
import com.tridung.caloriesdetect.exception.ErrorCode;
import com.tridung.caloriesdetect.service.ImageStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class CloudinaryImageStorageService implements ImageStorageService {
    public static final long MAX_IMAGE_SIZE = 10 * 1024 * 1024;
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private final Cloudinary cloudinary;

    @Override
    public StoredImage upload(MultipartFile file) {
        return upload(file, "calories-detect/avatars");
    }

    @Override
    public StoredImage upload(MultipartFile file, long userId, long mealId) {
        return upload(file, "calories-detect/meals/" + userId + "/" + mealId);
    }

    private StoredImage upload(MultipartFile file, String assetFolder) {
        if (file == null || file.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_IMAGE);
        }
        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new AppException(ErrorCode.IMAGE_TOO_LARGE);
        }
        byte[] bytes;
        try (var input = file.getInputStream()) {
            bytes = input.readNBytes((int) MAX_IMAGE_SIZE + 1);
        } catch (IOException exception) {
            throw new AppException(ErrorCode.IMAGE_UPLOAD_FAILED, exception);
        }
        if (bytes.length > MAX_IMAGE_SIZE) {
            throw new AppException(ErrorCode.IMAGE_TOO_LARGE);
        }
        if (!matchesImageType(bytes, file.getContentType())) {
            throw new AppException(ErrorCode.INVALID_IMAGE);
        }

        String publicId = assetFolder + "/" + UUID.randomUUID();
        try {
            Map<?, ?> result = cloudinary.uploader().upload(bytes, Map.of(
                    "public_id", publicId,
                    // Dynamic folders are independent of the public ID's path.
                    "asset_folder", assetFolder,
                    "resource_type", "image",
                    "overwrite", false,
                    "allowed_formats", List.of("jpg", "png", "webp")
            ));
            if (result == null || !(result.get("public_id") instanceof String id) || id.isBlank()
                    || !(result.get("secure_url") instanceof String url) || !url.startsWith("https://")) {
                throw new AppException(ErrorCode.IMAGE_UPLOAD_FAILED);
            }
            return new StoredImage(id, url);
        } catch (IOException | RuntimeException exception) {
            log.error("Cloudinary upload failed for {}", publicId, exception);
            // A timeout or malformed response can occur after the asset was created.
            try {
                delete(publicId);
            } catch (AppException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
                log.error("Unable to clean up failed image upload {}", publicId, cleanupFailure);
            }
            throw new AppException(ErrorCode.IMAGE_UPLOAD_FAILED, exception);
        }
    }

    @Override
    public void delete(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            return;
        }
        try {
            Map<?, ?> result = cloudinary.uploader().destroy(publicId, Map.of(
                    "resource_type", "image", "invalidate", true
            ));
            // Deletion is idempotent, including retries after an ambiguous network failure.
            if (result == null || !("ok".equals(result.get("result")) || "not found".equals(result.get("result")))) {
                throw new AppException(ErrorCode.IMAGE_DELETE_FAILED);
            }
        } catch (IOException | RuntimeException exception) {
            throw new AppException(ErrorCode.IMAGE_DELETE_FAILED, exception);
        }
    }

    private boolean matchesImageType(byte[] bytes, String contentType) {
        if (contentType == null) return false;
        return switch (contentType) {
            case "image/jpeg" -> bytes.length >= 3 && bytes[0] == (byte) 0xff
                    && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff;
            case "image/png" -> bytes.length >= PNG_SIGNATURE.length
                    && Arrays.equals(bytes, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length);
            case "image/webp" -> bytes.length >= 16
                    && "RIFF".equals(new String(bytes, 0, 4, StandardCharsets.US_ASCII))
                    && "WEBP".equals(new String(bytes, 8, 4, StandardCharsets.US_ASCII))
                    && List.of("VP8 ", "VP8L", "VP8X").contains(new String(bytes, 12, 4, StandardCharsets.US_ASCII));
            default -> false;
        };
    }
}
