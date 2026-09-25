package com.tridung.caloriesdetect.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tridung.caloriesdetect.config.SecurityConfig;
import com.tridung.caloriesdetect.dto.request.auth.LoginRequest;
import com.tridung.caloriesdetect.dto.response.auth.LoginResponse;
import com.tridung.caloriesdetect.security.*;
import com.tridung.caloriesdetect.service.AuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = AuthController.class, properties = {
        "app.auth.secure-cookies=true", "app.auth.allowed-origins=https://caloriesdetect.com",
        "app.jwt.refresh-expiration=7d"
})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, AuthCookies.class})
class AuthCookieSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean JwtService jwt;
    @MockitoBean CustomUserDetailsService users;
    private final ObjectMapper json = new ObjectMapper();

    @Test void csrfBootstrapAndLoginUseSecureHostOnlyCookiesWithoutJsonRefreshToken() throws Exception {
        var bootstrap = mvc.perform(get("/api/auth/csrf").header("Origin", "https://caloriesdetect.com"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(cookie().httpOnly("__Host-calories_csrf", true))
                .andExpect(cookie().secure("__Host-calories_csrf", true))
                .andExpect(cookie().path("__Host-calories_csrf", "/"))
                .andReturn().getResponse();
        String csrf = json.readTree(bootstrap.getContentAsString()).path("data").path("token").asText();
        when(auth.login(any())).thenReturn(new LoginResponse("access", "secret-refresh", "Bearer", 900));
        var result = mvc.perform(post("/api/auth/login").cookie(bootstrap.getCookie("__Host-calories_csrf"))
                        .header("Origin", "https://caloriesdetect.com").header("X-XSRF-TOKEN", csrf)
                        .contentType("application/json").content("{\"email\":\"test@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(cookie().httpOnly("__Secure-calories_refresh", true))
                .andExpect(cookie().secure("__Secure-calories_refresh", true))
                .andExpect(cookie().path("__Secure-calories_refresh", "/api/auth"))
                .andExpect(cookie().maxAge("__Secure-calories_refresh", 604800))
                .andReturn().getResponse();
        assertThat(result.getHeader("Set-Cookie")).contains("SameSite=Lax").doesNotContain("Domain=");
        assertThat(result.getContentAsString()).doesNotContain("secret-refresh");
        verify(auth).login(new LoginRequest("test@example.com", "Password123!"));
    }

    @Test void cookieOperationsRejectMissingCsrfEvenWithBearerHeader() throws Exception {
        for (String path : new String[]{"login", "google", "refresh-token", "logout"}) {
            mvc.perform(post("/api/auth/" + path).header("Authorization", "Bearer anything")
                            .cookie(new Cookie("__Secure-calories_refresh", "refresh")))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40301));
        }
        verifyNoInteractions(auth);
    }

    @Test void forgedCsrfAndUntrustedOriginsAreRejected() throws Exception {
        mvc.perform(post("/api/auth/logout").cookie(new Cookie("__Host-calories_csrf", "real"))
                        .header("X-XSRF-TOKEN", "forged"))
                .andExpect(status().isForbidden());
        for (String origin : new String[]{"https://evil.example", "https://evil.caloriesdetect.com", "http://localhost:5173", "null"}) {
            mvc.perform(get("/api/auth/csrf").header("Origin", origin)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(auth);
    }

    @Test void credentialedPreflightAllowsOnlyConfiguredOriginAndHeaders() throws Exception {
        mvc.perform(options("/api/auth/refresh-token").header("Origin", "https://caloriesdetect.com")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type,X-XSRF-TOKEN"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://caloriesdetect.com"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withCsrf(String path) throws Exception {
        var response = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        return post(path).cookie(response.getCookie("__Host-calories_csrf"))
                .header("X-XSRF-TOKEN", json.readTree(response.getContentAsString()).path("data").path("token").asText());
    }

    @Test void logoutWorksWithoutAccessTokenAndClearsExactlyTheRefreshCookie() throws Exception {
        mvc.perform(withCsrf("/api/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("__Secure-calories_refresh", 0))
                .andExpect(cookie().path("__Secure-calories_refresh", "/api/auth"))
                .andExpect(cookie().secure("__Secure-calories_refresh", true));
        verifyNoInteractions(auth);
    }

    @Test void jsonRefreshCredentialsAreNeverAccepted() throws Exception {
        mvc.perform(withCsrf("/api/auth/refresh-token")
                        .contentType("application/json").content("{\"refreshToken\":\"legacy\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(auth);
    }
}
