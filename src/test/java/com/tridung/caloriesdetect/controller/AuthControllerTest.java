package com.tridung.caloriesdetect.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tridung.caloriesdetect.common.enums.UserRole;
import com.tridung.caloriesdetect.common.enums.UserStatus;
import com.tridung.caloriesdetect.dto.request.auth.ChangePasswordRequest;
import com.tridung.caloriesdetect.dto.request.auth.LoginRequest;
import com.tridung.caloriesdetect.dto.request.auth.LogoutRequest;
import com.tridung.caloriesdetect.dto.request.auth.RefreshTokenRequest;
import com.tridung.caloriesdetect.dto.request.auth.RegisterRequest;
import com.tridung.caloriesdetect.dto.response.LoginResponse;
import com.tridung.caloriesdetect.dto.response.RegisterResponse;
import com.tridung.caloriesdetect.entity.User;
import com.tridung.caloriesdetect.exception.AppException;
import com.tridung.caloriesdetect.exception.ErrorCode;
import com.tridung.caloriesdetect.security.CustomUserDetails;
import com.tridung.caloriesdetect.security.CustomUserDetailsService;
import com.tridung.caloriesdetect.security.JwtService;
import com.tridung.caloriesdetect.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
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
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"))
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

        mockMvc.perform(post("/api/auth/refresh-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Success"))
                .andExpect(jsonPath("$.data.accessToken").value("new-access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("new-refresh-token"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(900));
    }

    @Test
    void refreshToken_shouldReturnBadRequestWhenTokenBlank() throws Exception {
        RefreshTokenRequest request = new RefreshTokenRequest("");

        mockMvc.perform(post("/api/auth/refresh-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
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
                UserStatus.ACTIVE
        );

        when(authService.register(request)).thenReturn(response);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Success"))
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

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("Success"))
                .andExpect(jsonPath("$.data").value("Logout successfull"));

        verify(authService).logout(request);
    }

    @Test
    void changePassword_shouldReturnSuccess() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest(
                "old-password",
                "new-password123",
                "new-password123"
        );
        CustomUserDetails userDetails = new CustomUserDetails(testUser());

        doNothing().when(authService).changePassword(1L, request);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                )
        );

        try {
            mockMvc.perform(put("/api/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Success"))
                    .andExpect(jsonPath("$.data").value("Password changed successfully"));
        } finally {
            SecurityContextHolder.clearContext();
        }

        verify(authService).changePassword(1L, request);
    }

    @Test
    void changePassword_shouldReturnBadRequestWhenNewPasswordTooShort() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest(
                "old-password",
                "123",
                "123"
        );
        CustomUserDetails userDetails = new CustomUserDetails(testUser());

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                )
        );

        try {
            mockMvc.perform(put("/api/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void changePassword_shouldReturnBadRequestWhenCurrentPasswordBlank() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest(
                "",
                "new-password123",
                "new-password123"
        );
        CustomUserDetails userDetails = new CustomUserDetails(testUser());

        withAuthentication(userDetails);

        try {
            mockMvc.perform(put("/api/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void changePassword_shouldReturnBadRequestWhenNewPasswordTooLong() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest(
                "old-password",
                "a".repeat(73),
                "a".repeat(73)
        );
        CustomUserDetails userDetails = new CustomUserDetails(testUser());

        withAuthentication(userDetails);

        try {
            mockMvc.perform(put("/api/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void changePassword_shouldReturnBadRequestWhenCurrentPasswordInvalid() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest(
                "wrong-password",
                "new-password123",
                "new-password123"
        );
        CustomUserDetails userDetails = new CustomUserDetails(testUser());

        doThrow(new AppException(ErrorCode.OLD_PASSWORD_NOT_MATCH))
                .when(authService)
                .changePassword(1L, request);

        withAuthentication(userDetails);

        try {
            mockMvc.perform(put("/api/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(11002))
                    .andExpect(jsonPath("$.message").value("Old password not match"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void withAuthentication(CustomUserDetails userDetails) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                )
        );
    }

    private User testUser() {
        return User.builder()
                .id(1L)
                .email("test@gmail.com")
                .password("encoded-password")
                .fullName("Test User")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .build();
    }
}
