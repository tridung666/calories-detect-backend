package com.tridung.caloriesdetect.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@ConfigurationProperties(prefix = "app.auth")
public record AuthCookieProperties(
        @DefaultValue("true") boolean secureCookies,
        @DefaultValue("https://caloriesdetect.com") List<String> allowedOrigins
) {
    public String refreshCookieName() {
        return secureCookies ? "__Secure-calories_refresh" : "calories_refresh";
    }

    public String csrfCookieName() {
        return secureCookies ? "__Host-calories_csrf" : "calories_csrf";
    }
}
