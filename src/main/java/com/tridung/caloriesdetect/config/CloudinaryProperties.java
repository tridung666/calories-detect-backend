package com.tridung.caloriesdetect.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.cloudinary")
public record CloudinaryProperties(
        @NotBlank String cloudName,
        @NotBlank String apiKey,
        @NotBlank String apiSecret
) {
    @Override
    public String toString() {
        return "CloudinaryProperties[credentials=REDACTED]";
    }
}
