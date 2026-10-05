package com.tridung.caloriesdetect.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.ai-service")
public record AiServiceProperties(
        @NotNull URI baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration responseTimeout
) {
    @AssertTrue(message = "AI service base URL must be an absolute HTTP(S) URL without credentials, query or fragment")
    public boolean isBaseUrlValid() {
        return baseUrl != null && ("http".equalsIgnoreCase(baseUrl.getScheme())
                || "https".equalsIgnoreCase(baseUrl.getScheme())) && baseUrl.getHost() != null
                && baseUrl.getUserInfo() == null && baseUrl.getQuery() == null && baseUrl.getFragment() == null;
    }

    @AssertTrue(message = "AI service timeouts must be at least 1 millisecond")
    public boolean isTimeoutsValid() {
        return connectTimeout != null && responseTimeout != null
                && connectTimeout.toMillis() > 0 && responseTimeout.toMillis() > 0;
    }
}
