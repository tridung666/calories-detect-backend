package com.tridung.caloriesdetect.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.function.Consumer;

/** Coordinates storage changes with the caller's locked database transaction. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImageUpdateService {
    private final ImageStorageService storage;

    @Transactional(propagation = Propagation.MANDATORY)
    public void replace(MultipartFile file, String previousPublicId,
                        Consumer<ImageStorageService.StoredImage> saveAndFlush) {
        replaceUploadedImage(storage.upload(file), previousPublicId, saveAndFlush);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void replace(MultipartFile file, long userId, long mealId, String previousPublicId,
                        Consumer<ImageStorageService.StoredImage> saveAndFlush) {
        replaceUploadedImage(storage.upload(file, userId, mealId), previousPublicId, saveAndFlush);
    }

    private void replaceUploadedImage(ImageStorageService.StoredImage uploaded, String previousPublicId,
                                      Consumer<ImageStorageService.StoredImage> saveAndFlush) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        storage.delete(uploaded.publicId());
                    } catch (RuntimeException exception) {
                        // Preserve the original failure and record the asset for manual cleanup.
                        log.error("Unable to clean up rolled-back image {}", uploaded.publicId());
                    }
                }
            }
        });
        saveAndFlush.accept(uploaded);
        storage.delete(previousPublicId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void delete(String publicId, Runnable clearAndFlush) {
        clearAndFlush.run();
        storage.delete(publicId);
    }
}
