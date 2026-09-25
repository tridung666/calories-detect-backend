package com.tridung.caloriesdetect.security;

import com.tridung.caloriesdetect.config.AuthCookieProperties;
import com.tridung.caloriesdetect.config.JwtProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;

@Component
@RequiredArgsConstructor
public class AuthCookies {
    private final AuthCookieProperties properties;
    private final JwtProperties jwtProperties;
    public static final String PATH = "/api/auth";

    public String readRefreshToken(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(cookie -> properties.refreshCookieName().equals(cookie.getName()))
                .map(jakarta.servlet.http.Cookie::getValue).findFirst().orElse(null);
    }

    public void setRefreshToken(HttpServletResponse response, String token) {
        write(response, token, jwtProperties.refreshExpiration());
    }

    public void clearRefreshToken(HttpServletResponse response) {
        write(response, "", Duration.ZERO);
    }

    private void write(HttpServletResponse response, String value, Duration maxAge) {
        // Omit Domain: the API host alone owns the cookie, including in production.
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(properties.refreshCookieName(), value)
                .httpOnly(true).secure(properties.secureCookies()).sameSite("Lax")
                .path(PATH).maxAge(maxAge).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
