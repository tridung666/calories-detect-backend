package com.tridung.caloriesdetect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tridung.caloriesdetect.common.enums.*;
import com.tridung.caloriesdetect.dto.request.auth.*;
import com.tridung.caloriesdetect.entity.OtpToken;
import com.tridung.caloriesdetect.entity.User;
import com.tridung.caloriesdetect.entity.AuthProvider;
import com.tridung.caloriesdetect.repository.AuthProviderRepository;
import com.tridung.caloriesdetect.common.enums.AuthProviderType;

import com.tridung.caloriesdetect.exception.AppException;
import com.tridung.caloriesdetect.exception.ErrorCode;
import com.tridung.caloriesdetect.repository.*;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mail.MailSendException;
import jakarta.mail.internet.MimeMessage;
import com.tridung.caloriesdetect.support.EmailTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "management.health.mail.enabled=false")
@AutoConfigureMockMvc
class PasswordResetIntegrationTest {
    @Autowired AuthService auth;
    @Autowired UserRepository users;
    @Autowired AuthProviderRepository providers;
    @Autowired OtpTokenRepository tokens;
    @Autowired PasswordEncoder encoder;
    @Autowired MockMvc mvc;
    @MockitoBean JavaMailSender mailSender;
    @MockitoSpyBean RefreshTokenRepository refreshTokens;

    private final List<Long> createdUsers = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper();
    private static final String OLD = "OldPassword123!";
    private static final String NEW = "NewPassword456!";

    @BeforeEach
    void prepareHtmlMail() {
        EmailTestSupport.prepare(mailSender);
    }

    @AfterEach
    void cleanup() {
        for (Long id : createdUsers) users.deleteById(id);
    }

    private User user() {
        User user = users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@example.com")
                .fullName("Reset test").role(UserRole.USER)
                .status(UserStatus.ACTIVE).emailVerified(true).build());
        createdUsers.add(user.getId());
        providers.saveAndFlush(AuthProvider.builder().user(user).provider(AuthProviderType.LOCAL)
                .passwordHash(encoder.encode(OLD)).build());
        return user;
    }

    private void request(User user) {
        auth.forgotPassword(new ForgotPasswordRequest(user.getEmail()));
    }

    private void confirm(User user, String otp) {
        auth.resetPassword(new ResetPasswordRequest(user.getEmail(), otp, NEW, NEW));
    }

    private String lastOtp() {
        return EmailTestSupport.lastOtp(mailSender, "Password Reset");
    }

    private OtpToken latest(User user) {
        return tokens.findFirstByUserIdAndPurposeOrderByIdDesc(user.getId(), OtpPurpose.PASSWORD_RESET).orElseThrow();
    }

    private void allowResend(User user) {
        var token = latest(user);
        token.setExpiresAt(Instant.now().plusSeconds(180));
        tokens.saveAndFlush(token);
    }

    private void assertError(ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action).isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(code);
    }

    @Test
    void publicEndpointsResetPasswordRevokeSessions() throws Exception {
        User user = user();
        var first = auth.login(new LoginRequest(user.getEmail(), OLD));
        var second = auth.login(new LoginRequest(user.getEmail(), OLD));
        User other = user();
        var otherLogin = auth.login(new LoginRequest(other.getEmail(), OLD));
        Instant start = Instant.now();
        mvc.perform(post("/api/auth/forgot-password").contentType("application/json")
                        .content(json.writeValueAsString(new ForgotPasswordRequest(user.getEmail().toUpperCase(Locale.ROOT)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        String otp = lastOtp();
        assertThat(latest(user).getOtpHash()).isNotEqualTo(otp);
        assertThat(encoder.matches(otp, latest(user).getOtpHash())).isTrue();
        assertThat(latest(user).getExpiresAt()).isBetween(start.plusSeconds(300), Instant.now().plusSeconds(300));
        assertThat(encoder.matches(OLD, providers.findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL).orElseThrow().getPasswordHash())).isTrue();
        mvc.perform(post("/api/auth/reset-password").contentType("application/json")
                        .content(json.writeValueAsString(new ResetPasswordRequest(user.getEmail(), otp, NEW, NEW))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("Password reset successfully. Please log in again"));
        assertThat(latest(user).getIsUsed()).isTrue();
        for (var login : List.of(first, second)) {
            assertError(() -> auth.refreshToken(new RefreshTokenRequest(login.refreshToken())), ErrorCode.REFRESH_TOKEN_REVOKED);
        }
        assertThat(auth.refreshToken(new RefreshTokenRequest(otherLogin.refreshToken())).accessToken()).isNotBlank();
        assertError(() -> auth.login(new LoginRequest(user.getEmail(), OLD)), ErrorCode.INVALID_CREDENTIALS);
        assertThat(auth.login(new LoginRequest(user.getEmail(), NEW)).accessToken()).isNotBlank();
        assertError(() -> confirm(user, otp), ErrorCode.INVALID_OTP);
    }

    @Test
    void requestResponseIsUniformForUnknownIneligibleThrottledAndMailFailure() throws Exception {
        User eligible = user();
        User inactive = user(); inactive.setStatus(UserStatus.INACTIVE); users.saveAndFlush(inactive);
        User unverified = user(); unverified.setEmailVerified(false); users.saveAndFlush(unverified);
        User google = user(); providers.delete(providers.findByUserIdAndProvider(google.getId(), AuthProviderType.LOCAL).orElseThrow()); users.saveAndFlush(google);
        String expected = null;
        for (String email : List.of(UUID.randomUUID() + "@example.com", inactive.getEmail(), unverified.getEmail(),
                google.getEmail(), eligible.getEmail(), eligible.getEmail())) {
            String body = mvc.perform(post("/api/auth/forgot-password").contentType("application/json")
                            .content(json.writeValueAsString(new ForgotPasswordRequest(email))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            if (expected == null) expected = body;
            assertThat(body).isEqualTo(expected);
        }
        verify(mailSender, times(1)).send(any(MimeMessage.class));
        Long previous = latest(eligible).getId();
        allowResend(eligible);
        doThrow(new MailSendException("Private SMTP details")).when(mailSender).send(any(MimeMessage.class));
        String failed = mvc.perform(post("/api/auth/forgot-password").contentType("application/json")
                        .content(json.writeValueAsString(new ForgotPasswordRequest(eligible.getEmail()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(failed).isEqualTo(expected);
        assertThat(latest(eligible).getId()).isEqualTo(previous);
        assertThat(latest(eligible).getIsUsed()).isFalse();
    }

    @Test
    void resetRejectsUnknownInactiveUnverifiedAndGoogleOnlyAccountsWithSameError() {
        assertError(() -> auth.resetPassword(new ResetPasswordRequest(UUID.randomUUID() + "@example.com", "123456", NEW, NEW)), ErrorCode.INVALID_OTP);
        for (int state = 0; state < 3; state++) {
            User user = user(); request(user); String otp = lastOtp();
            if (state == 0) user.setEmailVerified(false);
            if (state == 1) user.setStatus(UserStatus.INACTIVE);
            if (state == 2) providers.delete(providers.findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL).orElseThrow());
            users.saveAndFlush(user);
            assertError(() -> confirm(user, otp), ErrorCode.INVALID_OTP);
            assertThat(latest(user).getIsUsed()).isFalse();
        }
    }

    @Test
    void failedAttemptsPersistAndFifthAttemptLocksCode() {
        User user = user(); request(user); String otp = lastOtp();
        String wrong = otp.equals("000000") ? "999999" : "000000";
        for (int i = 1; i <= 5; i++) {
            assertError(() -> confirm(user, wrong), ErrorCode.INVALID_OTP);
            assertThat(latest(user).getAttemptCount()).isEqualTo(i);
        }
        assertError(() -> confirm(user, otp), ErrorCode.INVALID_OTP);
        assertThat(encoder.matches(OLD, providers.findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void expiredCodeIsRejected() {
        User user = user(); request(user); String otp = lastOtp();
        var token = latest(user); token.setExpiresAt(Instant.now().minusSeconds(1)); tokens.saveAndFlush(token);
        assertError(() -> confirm(user, otp), ErrorCode.INVALID_OTP);
    }

    @Test
    void resendInvalidatesPreviousCodeAndEnforcesCooldownAndHourlyLimit() {
        User user = user(); request(user); String oldOtp = lastOtp(); Long first = latest(user).getId();
        request(user);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
        allowResend(user); request(user);
        assertThat(lastOtp()).isNotEqualTo(oldOtp);
        assertThat(tokens.findById(first).orElseThrow().getIsUsed()).isTrue();
        assertError(() -> confirm(user, oldOtp), ErrorCode.INVALID_OTP);
        for (int i = 0; i < 3; i++) { allowResend(user); request(user); }
        allowResend(user); request(user);
        verify(mailSender, times(5)).send(any(MimeMessage.class));
        confirm(user, lastOtp());
    }

    @Test
    void codesAreIsolatedByUserAndPurpose() {
        User user = user(); User other = user();
        for (var purpose : List.of(OtpPurpose.EMAIL_VERIFICATION)) {
            tokens.saveAndFlush(OtpToken.builder().user(user).purpose(purpose)
                    .otpHash(encoder.encode("123456")).expiresAt(Instant.now().plusSeconds(300)).build());
        }
        assertError(() -> confirm(user, "123456"), ErrorCode.INVALID_OTP);
        request(user); String otp = lastOtp();
        assertError(() -> confirm(other, otp), ErrorCode.INVALID_OTP);
        assertThat(latest(user).getAttemptCount()).isZero();
        confirm(user, otp);
    }

    @Test
    void invalidPasswordsDoNotConsumeCode() {
        User user = user(); request(user); String otp = lastOtp();
        assertError(() -> auth.resetPassword(new ResetPasswordRequest(user.getEmail(), otp, NEW, OLD)), ErrorCode.PASSWORD_CONFIRM_NOT_MATCH);
        for (String password : List.of("short", "x".repeat(73), "é".repeat(37), " ".repeat(8))) {
            assertError(() -> auth.resetPassword(new ResetPasswordRequest(user.getEmail(), otp, password, password)), ErrorCode.INVALID_NEW_PASSWORD);
        }
        assertThat(latest(user).getIsUsed()).isFalse();
        confirm(user, otp);
    }

    @Test
    void databaseFailureRollsBackPasswordAndOtp() {
        User user = user(); var login = auth.login(new LoginRequest(user.getEmail(), OLD));
        request(user); String otp = lastOtp();
        doThrow(new DataAccessResourceFailureException("Simulated failure"))
                .when(refreshTokens).revokeAllByUserId(eq(user.getId()), any(LocalDateTime.class));
        assertThatThrownBy(() -> confirm(user, otp)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(latest(user).getIsUsed()).isFalse();
        assertThat(encoder.matches(OLD, providers.findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL).orElseThrow().getPasswordHash())).isTrue();
        assertThat(auth.refreshToken(new RefreshTokenRequest(login.refreshToken())).accessToken()).isNotBlank();
    }

    @Test
    void concurrentRequestsIssueOneCodeAndConcurrentConfirmationsSucceedOnce() throws Exception {
        User user = user();
        concurrently(() -> { request(user); return true; });
        verify(mailSender, times(1)).send(any(MimeMessage.class));
        String otp = lastOtp();
        assertThat(concurrently(() -> {
            try { confirm(user, otp); return true; }
            catch (AppException e) { assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_OTP); return false; }
        })).containsExactlyInAnyOrder(true, false);
    }

    private List<Boolean> concurrently(Callable<Boolean> action) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> task = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start timed out");
                return action.call();
            };
            var first = executor.submit(task); var second = executor.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void publicEndpointsValidatePayloads() throws Exception {
        for (String body : List.of("{}", "{\"email\":\"invalid-email\"}")) {
            mvc.perform(post("/api/auth/forgot-password").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        for (var request : List.of(
                new ResetPasswordRequest("invalid-email", "123456", NEW, NEW),
                new ResetPasswordRequest("user@example.com", "12x456", NEW, NEW),
                new ResetPasswordRequest("user@example.com", "123456", "short", "short"),
                new ResetPasswordRequest("user@example.com", "123456", NEW, ""))) {
            mvc.perform(post("/api/auth/reset-password").contentType("application/json").content(json.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/auth/reset-password").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(mailSender);
    }
}
