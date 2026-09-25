package com.tridung.caloriesdetect.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tridung.caloriesdetect.common.enums.UserRole;
import com.tridung.caloriesdetect.common.enums.UserStatus;
import com.tridung.caloriesdetect.dto.request.auth.RequestPasswordChangeRequest;
import com.tridung.caloriesdetect.dto.request.auth.ResetPasswordRequest;
import com.tridung.caloriesdetect.dto.request.auth.GoogleLoginRequest;
import com.tridung.caloriesdetect.dto.request.auth.LoginRequest;
import com.tridung.caloriesdetect.dto.request.auth.LogoutRequest;
import com.tridung.caloriesdetect.dto.request.auth.RefreshTokenRequest;
import com.tridung.caloriesdetect.dto.request.auth.RegisterRequest;
import com.tridung.caloriesdetect.dto.response.auth.LoginResponse;
import com.tridung.caloriesdetect.dto.response.auth.RegisterResponse;
import com.tridung.caloriesdetect.exception.AppException;
import com.tridung.caloriesdetect.exception.ErrorCode;
import com.tridung.caloriesdetect.security.CustomUserDetailsService;
import com.tridung.caloriesdetect.security.JwtService;
import com.tridung.caloriesdetect.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.tridung.caloriesdetect.config.AuthCookieProperties;
import com.tridung.caloriesdetect.config.JwtProperties;
import com.tridung.caloriesdetect.security.AuthCookies;
import jakarta.servlet.http.Cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = AuthController.class, properties = {"app.auth.secure-cookies=false", "app.jwt.refresh-expiration=7d"})
@Import(AuthCookies.class)
@EnableConfigurationProperties({AuthCookieProperties.class, JwtProperties.class})
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void login_shouldReturnLoginResponse() throws Exception {
        LoginRequest request = new LoginRequest("test@gmail.com", "password123");
        LoginResponse response = new LoginResponse(
                "access-token",
                "refresh-token",
                "Bearer",
                900L
        );

        when(authService.login(request)).thenReturn(response);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Success"))
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(cookie().httpOnly("calories_refresh", true))
                .andExpect(cookie().path("calories_refresh", "/api/auth"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(900));
    }

    @Test
    void login_shouldReturnBadRequestWhenEmailInvalid() throws Exception {
        LoginRequest request = new LoginRequest("invalid-email", "password123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginWithGoogle_shouldReturnLoginResponse() throws Exception {
        GoogleLoginRequest request = new GoogleLoginRequest("google-id-token");
        LoginResponse response = new LoginResponse(
                "access-token", "refresh-token", "Bearer", 900L
        );

        when(authService.loginWithGoogle(request)).thenReturn(response);

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(cookie().httpOnly("calories_refresh", true))
                .andExpect(cookie().path("calories_refresh", "/api/auth"))
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(authService).loginWithGoogle(request);
    }

    @Test
    void loginWithGoogle_shouldRejectBlankIdToken() throws Exception {
        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_shouldReturnInvalidCredentialsWhenEmailDoesNotExist() throws Exception {
        LoginRequest request = new LoginRequest("unknown@gmail.com", "password123");

        when(authService.login(request))
                .thenThrow(new AppException(ErrorCode.INVALID_CREDENTIALS));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(11001))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void refreshToken_shouldReturnNewTokens() throws Exception {
        RefreshTokenRequest request = new RefreshTokenRequest("old-refresh-token");
        LoginResponse response = new LoginResponse(
                "new-access-token",
                "new-refresh-token",
                "Bearer",
                900L
        );

        when(authService.refreshToken(request)).thenReturn(response);

        mockMvc.perform(post("/api/auth/refresh-token").cookie(new Cookie("calories_refresh", request.refreshToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Success"))
                .andExpect(jsonPath("$.data.accessToken").value("new-access-token"))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(cookie().value("calories_refresh", "new-refresh-token"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(900));
    }

    @Test
    void refreshToken_shouldReturnUnauthorizedWhenTokenBlank() throws Exception {
        RefreshTokenRequest request = new RefreshTokenRequest("");

        mockMvc.perform(post("/api/auth/refresh-token").cookie(new Cookie("calories_refresh", request.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void register_shouldReturnRegisterResponse() throws Exception {
        RegisterRequest request = new RegisterRequest(
                "test@gmail.com",
                "password123",
                "Test User"
        );
        RegisterResponse response = new RegisterResponse(
                1L,
                "test@gmail.com",
                "Test User",
                UserRole.USER,
                UserStatus.ACTIVE,
                false
        );

        when(authService.register(request)).thenReturn(response);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Registration successful. Please verify your email using the OTP sent to your inbox"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.email").value("test@gmail.com"))
                .andExpect(jsonPath("$.data.fullName").value("Test User"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void register_shouldReturnBadRequestWhenPasswordTooShort() throws Exception {
        RegisterRequest request = new RegisterRequest(
                "test@gmail.com",
                "123",
                "Test User"
        );

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void logout_shouldReturnSuccess() throws Exception {
        LogoutRequest request = new LogoutRequest("refresh-token");

        doNothing().when(authService).logout(request);

        mockMvc.perform(post("/api/auth/logout").cookie(new Cookie("calories_refresh", request.refreshToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Success"))
                .andExpect(jsonPath("$.data").value("Logout successful"))
                .andExpect(cookie().maxAge("calories_refresh", 0));

        verify(authService).logout(request);
    }

    @Test
    void requestPasswordChange_shouldReturnInstructions() throws Exception {
        var request = new RequestPasswordChangeRequest("old-password");
        mockMvc.perform(post("/api/auth/change-password/request").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(authService).requestPasswordChange(request);
    }

    @Test
    void resetPassword_shouldRequireLoginAgain() throws Exception {
        var request = new ResetPasswordRequest("user@example.com", "123456", "new-password123", "new-password123");
        mockMvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("Password reset successfully. Please log in again"));
        verify(authService).resetPassword(request);
    }

    @Test
    void requestPasswordChange_shouldRejectBlankCurrentPassword() throws Exception {
        mockMvc.perform(post("/api/auth/change-password/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetPassword_shouldValidateOtpAndPassword() throws Exception {
        for (var request : java.util.List.of(
                new ResetPasswordRequest("user@example.com", "12x456", "new-password123", "new-password123"),
                new ResetPasswordRequest("user@example.com", "123456", "short", "short"),
                new ResetPasswordRequest("user@example.com", "123456", "x".repeat(73), "x".repeat(73)))) {
            mockMvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }
}
