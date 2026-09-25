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
import com.tridung.caloriesdetect.security.CustomUserDetails;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import jakarta.mail.internet.MimeMessage;
import com.tridung.caloriesdetect.support.EmailTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "management.health.mail.enabled=false")
@AutoConfigureMockMvc
class PasswordChangeIntegrationTest {
    @Autowired AuthService auth;
    @Autowired UserRepository users;
    @Autowired AuthProviderRepository providers;
    @Autowired OtpTokenRepository tokens;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
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
        SecurityContextHolder.clearContext();
        for (Long id : createdUsers) users.deleteById(id); // FK cascades remove tokens.
    }

    private User user() {
        User user = users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@example.com")
                .fullName("Password test")
                .role(UserRole.USER).status(UserStatus.ACTIVE).emailVerified(true).build());
        createdUsers.add(user.getId());
        providers.saveAndFlush(AuthProvider.builder().user(user).provider(AuthProviderType.LOCAL)
                .passwordHash(encoder.encode(OLD)).build());
        return user;
    }

    private <T> T as(User user, Callable<T> action) throws Exception {
        var context = SecurityContextHolder.createEmptyContext();
        var principal = new CustomUserDetails(user);
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        try { return action.call(); } finally { SecurityContextHolder.clearContext(); }
    }

    private void request(User user) throws Exception {
        as(user, () -> { auth.requestPasswordChange(new RequestPasswordChangeRequest(OLD)); return null; });
    }

    private void confirm(User user, String otp, String password, String confirmation) throws Exception {
        auth.resetPassword(new ResetPasswordRequest(user.getEmail(), otp, password, confirmation));
    }

    private String lastOtp() {
        return EmailTestSupport.lastOtp(mailSender, "Password Reset");
    }

    private OtpToken latest(User user) {
        return tokens.findFirstByUserIdAndPurposeOrderByIdDesc(user.getId(), OtpPurpose.PASSWORD_RESET).orElseThrow();
    }

    private void allowResend(User user) {
        OtpToken token = latest(user);
        token.setExpiresAt(Instant.now().plusSeconds(180));
        tokens.saveAndFlush(token);
    }

    private void assertError(ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action).isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(code);
    }

    private long activeRefreshTokens(User user) {
        return jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null", Long.class, user.getId());
    }

    @Test
    void requestOnlySendsHashedPurposeBoundOtpThenConfirmChangesPasswordAndRevokesAllSessions() throws Exception {
        User user = user();
        var firstLogin = auth.login(new LoginRequest(user.getEmail(), OLD));
        var secondLogin = auth.login(new LoginRequest(user.getEmail(), OLD));
        User other = user();
        auth.login(new LoginRequest(other.getEmail(), OLD));
        Instant start = Instant.now();
        request(user);
        String otp = lastOtp();
        var token = latest(user);
        assertThat(token.getPurpose()).isEqualTo(OtpPurpose.PASSWORD_RESET);
        assertThat(encoder.matches(otp, token.getOtpHash())).isTrue();
        assertThat(token.getExpiresAt()).isBetween(start.plusSeconds(300), Instant.now().plusSeconds(300));
        assertThat(encoder.matches(OLD, providers.findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL).orElseThrow().getPasswordHash())).isTrue();
        assertThat(activeRefreshTokens(user)).isEqualTo(2);
        confirm(user, otp, NEW, NEW);
        assertThat(encoder.matches(NEW, providers.findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL).orElseThrow().getPasswordHash())).isTrue();
        assertThat(latest(user).getIsUsed()).isTrue();
        assertThat(activeRefreshTokens(user)).isZero();
        assertThat(activeRefreshTokens(other)).isEqualTo(1);
        for (var login : List.of(firstLogin, secondLogin)) {
            assertError(() -> auth.refreshToken(new RefreshTokenRequest(login.refreshToken())), ErrorCode.REFRESH_TOKEN_REVOKED);
        }
        assertError(() -> auth.login(new LoginRequest(user.getEmail(), OLD)), ErrorCode.INVALID_CREDENTIALS);
        assertThat(auth.login(new LoginRequest(user.getEmail(), NEW)).accessToken()).isNotBlank();
        // Access JWTs remain stateless: explicitly test/document that they can still authenticate.
        mvc.perform(post("/api/auth/change-password/request").header("Authorization", "Bearer " + firstLogin.accessToken())
                        .contentType("application/json").content(json.writeValueAsString(new RequestPasswordChangeRequest(NEW))))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value(11010));
    }

    @Test
    void incorrectCurrentPasswordDoesNotCreateOtp() {
        User user = user();
        assertError(() -> as(user, () -> {
            auth.requestPasswordChange(new RequestPasswordChangeRequest("wrong-password")); return null;
        }), ErrorCode.OLD_PASSWORD_NOT_MATCH);
        assertThat(tokens.findFirstByUserIdAndPurposeOrderByIdDesc(user.getId(), OtpPurpose.PASSWORD_RESET)).isEmpty();
        verifyNoInteractions(mailSender);
    }

    @Test
    void fifthIncorrectOtpPersistsAttemptsAndBlocksEvenTheCorrectCode() throws Exception {
        User user = user(); request(user);
        String otp = lastOtp();
        String wrong = otp.equals("000000") ? "999999" : "000000";
        for (int i = 1; i <= 5; i++) {
            assertError(() -> confirm(user, wrong, NEW, NEW), ErrorCode.INVALID_OTP);
            assertThat(latest(user).getAttemptCount()).isEqualTo(i);
        }
        assertError(() -> confirm(user, otp, NEW, NEW), ErrorCode.INVALID_OTP);
        assertThat(latest(user).getIsUsed()).isTrue();
        assertThat(encoder.matches(OLD, providers.findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void expiredOtpIsRejected() throws Exception {
        User user = user(); request(user);
        String otp = lastOtp(); var token = latest(user);
        token.setExpiresAt(Instant.now().minusSeconds(1)); tokens.saveAndFlush(token);
        assertError(() -> confirm(user, otp, NEW, NEW), ErrorCode.INVALID_OTP);
    }

    @Test
    void reusedOtpIsRejected() throws Exception {
        User user = user(); request(user); String otp = lastOtp();
        confirm(user, otp, NEW, NEW);
        assertError(() -> confirm(user, otp, "AnotherPassword789!", "AnotherPassword789!"), ErrorCode.INVALID_OTP);
    }

    @Test
    void googleOnlyAccountsCannotRequestOrConfirmAndCannotUseLocalLogin() throws Exception {
        User user = user(); providers.saveAndFlush(AuthProvider.builder().user(user).provider(AuthProviderType.GOOGLE)
                .providerSubject("subject-" + UUID.randomUUID()).build()); providers.delete(providers.findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL).orElseThrow());
        users.saveAndFlush(user);
        assertError(() -> request(user), ErrorCode.LOCAL_PASSWORD_REQUIRED);
        assertError(() -> confirm(user, "123456", NEW, NEW), ErrorCode.INVALID_OTP);
        assertError(() -> auth.login(new LoginRequest(user.getEmail(), OLD)), ErrorCode.INVALID_CREDENTIALS);
        verifyNoInteractions(mailSender);
    }

    @Test
    void explicitlyLinkedAccountWithLocalPasswordCanChangeIt() throws Exception {
        User user = user(); providers.saveAndFlush(AuthProvider.builder().user(user).provider(AuthProviderType.GOOGLE)
                .providerSubject("subject-" + UUID.randomUUID()).build()); users.saveAndFlush(user);
        request(user); confirm(user, lastOtp(), NEW, NEW);
        assertThat(providers.findByUserIdAndProvider(user.getId(), AuthProviderType.GOOGLE)).isPresent();
    }

    @Test
    void mismatchedAndInvalidPasswordsDoNotConsumeOtp() throws Exception {
        User user = user(); request(user); String otp = lastOtp();
        assertError(() -> confirm(user, otp, NEW, "DifferentPassword!"), ErrorCode.PASSWORD_CONFIRM_NOT_MATCH);
        for (String password : List.of("short", "x".repeat(73), "é".repeat(37), " ".repeat(8))) {
            assertError(() -> confirm(user, otp, password, password), ErrorCode.INVALID_NEW_PASSWORD);
        }
        assertThat(latest(user).getIsUsed()).isFalse();
        confirm(user, otp, NEW, NEW);
    }

    @Test
    void otpIsIsolatedByPurposeAndUser() throws Exception {
        User user = user(); User other = user();
        for (OtpPurpose purpose : List.of(OtpPurpose.EMAIL_VERIFICATION)) {
            tokens.saveAndFlush(OtpToken.builder().user(user).purpose(purpose).otpHash(encoder.encode("123456"))
                    .expiresAt(Instant.now().plusSeconds(300)).build());
        }
        assertError(() -> confirm(user, "123456", NEW, NEW), ErrorCode.INVALID_OTP);
        request(user); String otp = lastOtp();
        assertError(() -> confirm(other, otp, NEW, NEW), ErrorCode.INVALID_OTP);
        assertThat(latest(user).getAttemptCount()).isZero();
        confirm(user, otp, NEW, NEW);
        for (OtpPurpose purpose : List.of(OtpPurpose.EMAIL_VERIFICATION)) {
            assertThat(tokens.findFirstByUserIdAndPurposeOrderByIdDesc(user.getId(), purpose).orElseThrow().getIsUsed()).isFalse();
        }
    }

    @Test
    void changePasswordOtpCannotVerifyEmail() throws Exception {
        User user = user(); request(user); String otp = lastOtp();
        user.setEmailVerified(false); users.saveAndFlush(user);
        assertError(() -> auth.verifyEmail(new VerifyEmailRequest(user.getEmail(), otp)), ErrorCode.INVALID_OTP);
        assertThat(latest(user).getIsUsed()).isFalse();
    }

    @Test
    void resendInvalidatesOldOtpAndEnforcesCooldownAndHourlyLimit() throws Exception {
        User user = user(); request(user); String oldOtp = lastOtp(); Long firstId = latest(user).getId();
        assertError(() -> request(user), ErrorCode.OTP_RATE_LIMITED);
        allowResend(user); request(user); String newOtp = lastOtp();
        assertThat(tokens.findById(firstId).orElseThrow().getIsUsed()).isTrue();
        assertThat(newOtp.equals(oldOtp)).isFalse();
        assertError(() -> confirm(user, oldOtp, NEW, NEW), ErrorCode.INVALID_OTP);
        for (int i = 0; i < 3; i++) { allowResend(user); request(user); }
        allowResend(user);
        assertError(() -> request(user), ErrorCode.OTP_RATE_LIMITED);
        verify(mailSender, times(5)).send(any(MimeMessage.class));
        confirm(user, lastOtp(), NEW, NEW);
    }

    @Test
    void bothRequestRoutesShareCooldownHourlyLimitAndReplaceEachOthersCodes() throws Exception {
        User user = user();
        auth.forgotPassword(new ForgotPasswordRequest(user.getEmail()));
        String forgottenOtp = lastOtp();
        assertError(() -> request(user), ErrorCode.OTP_RATE_LIMITED);
        allowResend(user); request(user);
        String changeOtp = lastOtp();
        assertThat(changeOtp).isNotEqualTo(forgottenOtp);
        assertError(() -> confirm(user, forgottenOtp, NEW, NEW), ErrorCode.INVALID_OTP);
        auth.forgotPassword(new ForgotPasswordRequest(user.getEmail()));
        verify(mailSender, times(2)).send(any(MimeMessage.class));
        allowResend(user); auth.forgotPassword(new ForgotPasswordRequest(user.getEmail()));
        assertError(() -> confirm(user, changeOtp, NEW, NEW), ErrorCode.INVALID_OTP);
        allowResend(user); request(user);
        allowResend(user); auth.forgotPassword(new ForgotPasswordRequest(user.getEmail()));
        allowResend(user);
        assertError(() -> request(user), ErrorCode.OTP_RATE_LIMITED);
        auth.forgotPassword(new ForgotPasswordRequest(user.getEmail()));
        verify(mailSender, times(5)).send(any(MimeMessage.class));
        confirm(user, lastOtp(), NEW, NEW);
    }

    @Test
    void concurrentRequestsAcrossBothRoutesIssueOnlyOneCode() throws Exception {
        User user = user();
        concurrently(() -> { auth.forgotPassword(new ForgotPasswordRequest(user.getEmail())); return true; }, () -> {
            try { request(user); return true; }
            catch (AppException e) { assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OTP_RATE_LIMITED); return false; }
        });
        verify(mailSender, times(1)).send(any(MimeMessage.class));
        confirm(user, lastOtp(), NEW, NEW);
    }

    @Test
    void emailFailurePreservesPreviousOtpAndPassword() throws Exception {
        User user = user(); request(user); Long previous = latest(user).getId(); allowResend(user);
        doThrow(new MailSendException("Private SMTP detail")).when(mailSender).send(any(MimeMessage.class));
        assertError(() -> request(user), ErrorCode.EMAIL_DELIVERY_FAILED);
        assertThat(latest(user).getId()).isEqualTo(previous);
        assertThat(latest(user).getIsUsed()).isFalse();
        assertThat(encoder.matches(OLD, providers.findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void databaseFailureRollsBackPasswordOtpAndRefreshRevocation() throws Exception {
        User user = user(); auth.login(new LoginRequest(user.getEmail(), OLD)); request(user); String otp = lastOtp();
        doThrow(new DataAccessResourceFailureException("Simulated database failure"))
                .when(refreshTokens).revokeAllByUserId(eq(user.getId()), any(LocalDateTime.class));
        assertThatThrownBy(() -> confirm(user, otp, NEW, NEW)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(latest(user).getIsUsed()).isFalse();
        assertThat(encoder.matches(OLD, providers.findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL).orElseThrow().getPasswordHash())).isTrue();
        assertThat(activeRefreshTokens(user)).isEqualTo(1);
    }

    @Test
    void concurrentConfirmationsCanOnlyConsumeOtpOnce() throws Exception {
        User user = user(); request(user); String otp = lastOtp();
        Callable<Boolean> confirmation = () -> {
            try { confirm(user, otp, NEW, NEW); return true; }
            catch (AppException e) {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_OTP); return false;
            }
        };
        assertThat(concurrently(confirmation, confirmation)).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void concurrentResendsCannotBypassCooldown() throws Exception {
        User user = user();
        Callable<Boolean> send = () -> {
            try { request(user); return true; }
            catch (AppException e) { assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OTP_RATE_LIMITED); return false; }
        };
        assertThat(concurrently(send, send)).containsExactlyInAnyOrder(true, false);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void concurrentRefreshCannotEscapeRevocation() throws Exception {
        User user = user(); var login = auth.login(new LoginRequest(user.getEmail(), OLD)); request(user); String otp = lastOtp();
        concurrently(() -> { confirm(user, otp, NEW, NEW); return true; }, () -> {
            try { auth.refreshToken(new RefreshTokenRequest(login.refreshToken())); return true; }
            catch (AppException e) { assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REFRESH_TOKEN_REVOKED); return false; }
        });
        assertThat(activeRefreshTokens(user)).isZero();
    }

    @Test
    void concurrentLoginWithOldPasswordCannotEscapeRevocation() throws Exception {
        User user = user(); request(user); String otp = lastOtp();
        concurrently(() -> { confirm(user, otp, NEW, NEW); return true; }, () -> {
            try { auth.login(new LoginRequest(user.getEmail(), OLD)); return true; }
            catch (AppException e) { assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS); return false; }
        });
        assertThat(activeRefreshTokens(user)).isZero();
    }

    private List<Boolean> concurrently(Callable<Boolean> first, Callable<Boolean> second) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(action -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start timed out");
                return action.call();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void requestRequiresAuthenticationAndSharedConfirmationBindsOtpToEmail() throws Exception {
        mvc.perform(post("/api/auth/change-password/request").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        User user = user(); User victim = user();
        String access = auth.login(new LoginRequest(user.getEmail(), OLD)).accessToken();
        mvc.perform(post("/api/auth/change-password/request").header("Authorization", "Bearer " + access)
                        .contentType("application/json").content("{\"currentPassword\":\"" + OLD + "\",\"userId\":" + victim.getId() + ",\"email\":\"" + victim.getEmail() + "\"}"))
                .andExpect(status().isOk());
        String otp = lastOtp();
        mvc.perform(post("/api/auth/reset-password").contentType("application/json")
                        .content(json.writeValueAsString(new ResetPasswordRequest(victim.getEmail(), otp, NEW, NEW))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(11006));
        mvc.perform(post("/api/auth/reset-password").contentType("application/json")
                        .content(json.writeValueAsString(new ResetPasswordRequest(user.getEmail(), otp, NEW, NEW))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("Password reset successfully. Please log in again"));
        mvc.perform(post("/api/auth/change-password/confirm").header("Authorization", "Bearer " + access)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        assertThat(encoder.matches(OLD, providers.findByUserIdAndProvider(victim.getId(), AuthProviderType.LOCAL).orElseThrow().getPasswordHash())).isTrue();
        assertThat(encoder.matches(NEW, providers.findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL).orElseThrow().getPasswordHash())).isTrue();
        mvc.perform(put("/api/auth/change-password").header("Authorization", "Bearer " + access)
                        .contentType("application/json").content("{}"))
                .andExpect(status().is4xxClientError());
    }
}
