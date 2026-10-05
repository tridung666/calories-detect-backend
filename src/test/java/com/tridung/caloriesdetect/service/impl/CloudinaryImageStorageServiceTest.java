package com.tridung.caloriesdetect.service.impl;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.tridung.caloriesdetect.exception.AppException;
import com.tridung.caloriesdetect.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CloudinaryImageStorageServiceTest {
    private final Cloudinary cloudinary = mock(Cloudinary.class);
    private final Uploader uploader = mock(Uploader.class);
    private final CloudinaryImageStorageService storage = new CloudinaryImageStorageService(cloudinary);

    @BeforeEach void setup() {
        when(cloudinary.uploader()).thenReturn(uploader);
    }

    @ParameterizedTest @ValueSource(strings = {"jpeg", "png", "webp"})
    void acceptsSupportedImageTypes(String type) throws Exception {
        byte[] bytes = switch (type) {
            case "png" -> Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/Z1sAAAAASUVORK5CYII=");
            case "webp" -> Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
            default -> {
                var output = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", output);
                yield output.toByteArray();
            }
        };
        when(uploader.upload(any(), anyMap())).thenReturn(Map.of("public_id", "generated", "secure_url", "https://example.com/image"));
        var result = storage.upload(new MockMultipartFile("file", "ignored", "image/" + type, bytes));
        assertThat(result.publicId()).isEqualTo("generated");
        assertThat(result.secureUrl()).isEqualTo("https://example.com/image");
        verify(uploader).upload(eq(bytes), anyMap());
    }

    @ParameterizedTest @ValueSource(ints = {6, 10})
    void acceptsPhoneImagesUpToTenMiB(int sizeMiB) throws Exception {
        byte[] bytes = new byte[sizeMiB * 1024 * 1024];
        bytes[0] = (byte) 0xff;
        bytes[1] = (byte) 0xd8;
        bytes[2] = (byte) 0xff;
        when(uploader.upload(any(), anyMap())).thenReturn(Map.of("public_id", "generated", "secure_url", "https://example.com/image"));
        storage.upload(new MockMultipartFile("file", "image.jpg", "image/jpeg", bytes));
        verify(uploader).upload(eq(bytes), anyMap());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void uploadsIntoMediaLibraryFolderAndKeepsProviderId(boolean mealImage) throws Exception {
        when(uploader.upload(any(), anyMap())).thenReturn(Map.of(
                "public_id", "provider/returned-id", "secure_url", "https://example.com/image"));
        var result = mealImage ? storage.upload(jpeg(), 12L, 34L) : storage.upload(jpeg());

        ArgumentCaptor<Map> options = ArgumentCaptor.forClass(Map.class);
        verify(uploader).upload(any(), options.capture());
        String folder = mealImage ? "calories-detect/meals/12/34" : "calories-detect/avatars";
        assertThat(options.getValue().get("asset_folder")).isEqualTo(folder);
        String requestedId = (String) options.getValue().get("public_id");
        assertThat(requestedId).startsWith(folder + "/");
        assertThat(java.util.UUID.fromString(requestedId.substring(folder.length() + 1))).isNotNull();
        assertThat(options.getValue().get("overwrite")).isEqualTo(false);
        assertThat(result.publicId()).isEqualTo("provider/returned-id");
    }

    @Test void boundsStreamEvenWhenReportedSizeIsIncorrect() throws Exception {
        var file = mock(org.springframework.web.multipart.MultipartFile.class);
        when(file.getSize()).thenReturn(1L);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[(int) CloudinaryImageStorageService.MAX_IMAGE_SIZE + 1]));
        assertError(() -> storage.upload(file), ErrorCode.IMAGE_TOO_LARGE);
        verifyNoInteractions(uploader);
    }

    @Test void mapsFileReadFailure() throws Exception {
        var file = mock(org.springframework.web.multipart.MultipartFile.class);
        when(file.getInputStream()).thenThrow(new IOException("unreadable"));
        assertError(() -> storage.upload(file), ErrorCode.IMAGE_UPLOAD_FAILED);
        verifyNoInteractions(uploader);
    }

    @Test void rejectsNullFile() {
        assertError(() -> storage.upload(null), ErrorCode.INVALID_IMAGE);
        verifyNoInteractions(uploader);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void malformedUploadResponseTriggersCleanup(boolean mealImage) throws Exception {
        when(uploader.upload(any(), anyMap())).thenReturn(Map.of("public_id", "generated", "url", "http://example.com/image"));
        when(uploader.destroy(anyString(), anyMap())).thenReturn(Map.of("result", "ok"));
        assertError(() -> {
            if (mealImage) storage.upload(jpeg(), 12L, 34L);
            else storage.upload(jpeg());
        }, ErrorCode.IMAGE_UPLOAD_FAILED);
        ArgumentCaptor<Map> options = ArgumentCaptor.forClass(Map.class);
        verify(uploader).upload(any(), options.capture());
        String requestedId = (String) options.getValue().get("public_id");
        assertThat(requestedId).startsWith(mealImage ? "calories-detect/meals/12/34/" : "calories-detect/avatars/");
        verify(uploader).destroy(eq(requestedId), anyMap());
    }

    @Test void cleanupFailurePreservesUploadError() throws Exception {
        when(uploader.upload(any(), anyMap())).thenThrow(new IllegalArgumentException("provider failure"));
        when(uploader.destroy(anyString(), anyMap())).thenThrow(new IOException("delete failed"));
        assertError(() -> storage.upload(jpeg()), ErrorCode.IMAGE_UPLOAD_FAILED);
    }

    @Test void mapsUncheckedDeleteFailure() throws Exception {
        when(uploader.destroy(anyString(), anyMap())).thenThrow(new IllegalArgumentException("provider failure"));
        assertError(() -> storage.delete("image-id"), ErrorCode.IMAGE_DELETE_FAILED);
    }

    @Test void ignoresAbsentPublicId() {
        storage.delete(null);
        storage.delete("");
        verifyNoInteractions(uploader);
    }

    private MockMultipartFile jpeg() {
        return new MockMultipartFile("file", "image.jpg", "image/jpeg", new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(AppException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
