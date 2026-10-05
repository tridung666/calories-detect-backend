package com.tridung.caloriesdetect.service;

import org.springframework.web.multipart.MultipartFile;

public interface ImageStorageService {
    record StoredImage(String publicId, String secureUrl) {}

    StoredImage upload(MultipartFile file);

    StoredImage upload(MultipartFile file, long userId, long mealId);

    void delete(String publicId);
}
