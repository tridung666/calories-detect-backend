package com.tridung.caloriesdetect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.tridung.caloriesdetect.dto.request.auth.*;
import com.tridung.caloriesdetect.entity.OtpToken;
import com.tridung.caloriesdetect.entity.User;
import com.tridung.caloriesdetect.entity.AuthProvider;
import com.tridung.caloriesdetect.repository.AuthProviderRepository;
import com.tridung.caloriesdetect.common.enums.AuthProviderType;

import com.tridung.caloriesdetect.common.enums.OtpPurpose;
import com.tridung.caloriesdetect.exception.AppException;
import com.tridung.caloriesdetect.exception.ErrorCode;
import com.tridung.caloriesdetect.repository.OtpTokenRepository;
import com.tridung.caloriesdetect.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import jakarta.mail.internet.MimeMessage;
import com.tridung.caloriesdetect.support.EmailTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Runs against PostgreSQL: real transaction commits and row locks, with SMTP/Google mocked. */
@SpringBootTest(properties = "management.health.mail.enabled=false")
@AutoConfigureMockMvc
class EmailVerificationIntegrationTest {
    @Autowired AuthService auth;
    @Autowired UserRepository users;
    @Autowired AuthProviderRepository providers;
    @Autowired OtpTokenRepository tokens;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean JavaMailSender mailSender;
    @MockitoBean GoogleIdTokenVerifier googleVerifier;

    private final List<Long> createdUsers = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper();
    private static final String PASSWORD = "TestPassword123!";

    @BeforeEach
    void prepareHtmlMail() {
        EmailTestSupport.prepare(mailSender);
    }

    @AfterEach
    void cleanup() {
        for (Long id : createdUsers) {
            jdbc.update("delete from refresh_tokens where user_id = ?", id);
            jdbc.update("delete from otp_tokens where user_id = ?", id);
            users.deleteById(id);
        }
    }

    private User register() {
        String email = "otp-" + UUID.randomUUID() + "@example.com";
        var response = auth.register(new RegisterRequest(email.toUpperCase(), PASSWORD, "OTP test"));
        createdUsers.add(response.id());
        return users.findById(response.id()).orElseThrow();
    }

    private String lastOtp() {
        return EmailTestSupport.lastOtp(mailSender, "Email Verification");
    }

    private OtpToken latest(User user) {
        return tokens.findFirstByUserIdAndPurposeOrderByIdDesc(user.getId(), OtpPurpose.EMAIL_VERIFICATION).orElseThrow();
    }

    private void allowResend(User user) {
        var token = latest(user);
        // Issued two minutes ago: still valid, but past the resend cooldown.
        token.setExpiresAt(Instant.now().plusSeconds(180));
        tokens.saveAndFlush(token);
    }

    private void assertInvalid(User user, String otp) {
        assertThatThrownBy(() -> auth.verifyEmail(new VerifyEmailRequest(user.getEmail(), otp)))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_OTP);
    }

    @Test
    void registrationNormalizesEmailHashesSecretsAndIssuesNoTokens() {
        var start = Instant.now();
        User user = register();
        assertThat(user.getEmail()).isLowerCase();
        assertThat(user.getEmailVerified()).isFalse();
        assertThat(encoder.matches(PASSWORD, providers.findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL).orElseThrow().getPasswordHash())).isTrue();
        var token = latest(user);
        assertThat(encoder.matches(lastOtp(), token.getOtpHash())).isTrue();
        assertThat(token.getExpiresAt()).isBetween(start.plusSeconds(300), Instant.now().plusSeconds(300));
        assertThat(token.getAttemptCount()).isZero();
        assertThat(token.getIsUsed()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ?", Long.class, user.getId())).isZero();
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() {
        User user = register();
        auth.verifyEmail(new VerifyEmailRequest(user.getEmail(), lastOtp()));
        assertThatThrownBy(() -> auth.register(new RegisterRequest(" " + user.getEmail().toUpperCase() + " ", PASSWORD, "Duplicate")))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.USER_EXISTED);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void registeringAgainBeforeVerificationPreservesIdentityAndLocalCredentials() {
        User user = register();
        var local = providers.findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL).orElseThrow();
        var response = auth.register(new RegisterRequest(user.getEmail().toUpperCase(), "DifferentPassword123!", "Different name"));
        assertThat(response.id()).isEqualTo(user.getId());
        assertThat(users.findById(user.getId()).orElseThrow().getFullName()).isEqualTo(user.getFullName());
        assertThat(providers.findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL).orElseThrow().getPasswordHash())
                .isEqualTo(local.getPasswordHash());
        verify(mailSender, times(1)).send(any(MimeMessage.class));
        auth.verifyEmail(new VerifyEmailRequest(user.getEmail(), lastOtp()));
        assertThat(auth.login(new LoginRequest(user.getEmail(), PASSWORD)).accessToken()).isNotBlank();
    }

    @Test
    void verificationEnablesLoginAndOtpCannotBeReused() {
        User user = register();
        String otp = lastOtp();
        assertThatThrownBy(() -> auth.login(new LoginRequest(user.getEmail(), PASSWORD)))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);
        auth.verifyEmail(new VerifyEmailRequest(user.getEmail().toUpperCase(), otp));
        assertThat(users.findById(user.getId()).orElseThrow().getEmailVerified()).isTrue();
        assertThat(latest(user).getIsUsed()).isTrue();
        assertInvalid(user, otp);
        var login = auth.login(new LoginRequest(user.getEmail().toUpperCase(), PASSWORD));
        assertThat(login.accessToken()).isNotBlank();
        assertThat(login.refreshToken()).isNotBlank();
        assertThat(auth.refreshToken(new RefreshTokenRequest(login.refreshToken())).accessToken()).isNotBlank();
    }

    @Test
    void incorrectAttemptsCommitAndFifthFailureInvalidatesEvenCorrectOtp() {
        User user = register();
        String otp = lastOtp();
        String wrong = otp.equals("000000") ? "999999" : "000000";
        for (int i = 1; i <= 5; i++) {
            assertInvalid(user, wrong);
            assertThat(latest(user).getAttemptCount()).isEqualTo(i);
        }
        assertThat(latest(user).getIsUsed()).isTrue();
        assertInvalid(user, otp);
        assertThat(users.findById(user.getId()).orElseThrow().getEmailVerified()).isFalse();
    }

    @Test
    void expiredOtpIsRejected() {
        User user = register();
        var token = latest(user);
        token.setExpiresAt(Instant.now().minusSeconds(1));
        tokens.saveAndFlush(token);
        assertInvalid(user, lastOtp());
        assertThat(users.findById(user.getId()).orElseThrow().getEmailVerified()).isFalse();
    }

    @Test
    void resendHonorsCooldownInvalidatesPreviousCodeAndLimitsHourlyDelivery() {
        User user = register();
        var previous = latest(user);
        auth.resendOtp(new ResendOtpRequest(user.getEmail()));
        verify(mailSender, times(1)).send(any(MimeMessage.class));
        for (int i = 0; i < 4; i++) {
            allowResend(user);
            auth.resendOtp(new ResendOtpRequest(user.getEmail()));
        }
        assertThat(tokens.findById(previous.getId()).orElseThrow().getIsUsed()).isTrue();
        assertThat(latest(user).getAttemptCount()).isZero();
        assertThat(encoder.matches(lastOtp(), latest(user).getOtpHash())).isTrue();
        allowResend(user);
        auth.resendOtp(new ResendOtpRequest(user.getEmail()));
        verify(mailSender, times(5)).send(any(MimeMessage.class));
        assertThat(jdbc.queryForObject("select count(*) from otp_tokens where user_id = ? and not is_used", Long.class, user.getId())).isEqualTo(1);
    }

    @Test
    void verifiedAndUnknownAddressesHaveSameResendResponseAsCooldown() throws Exception {
        User user = register();
        String cooldown = resendResponse(user.getEmail());
        auth.verifyEmail(new VerifyEmailRequest(user.getEmail(), lastOtp()));
        assertThat(resendResponse(user.getEmail())).isEqualTo(cooldown);
        assertThat(resendResponse(UUID.randomUUID() + "@example.com")).isEqualTo(cooldown);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    private String resendResponse(String email) throws Exception {
        return mvc.perform(post("/api/auth/resend-otp").contentType("application/json")
                        .content(json.writeValueAsString(new ResendOtpRequest(email))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void smtpFailureRollsBackRegistrationAndReturnsSafeError() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        doThrow(new MailSendException("Private SMTP detail")).when(mailSender).send(any(MimeMessage.class));
        mvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(json.writeValueAsString(new RegisterRequest(email, PASSWORD, "OTP test"))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(11008))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Private SMTP detail"))));
        assertThat(users.existsByEmailIgnoreCase(email)).isFalse();
    }

    @Test
    void smtpFailureOnResendKeepsOldOtpAndDoesNotExposeAccountExistence() throws Exception {
        User user = register();
        allowResend(user);
        Long previousId = latest(user).getId();
        doThrow(new MailSendException("Private SMTP detail")).when(mailSender).send(any(MimeMessage.class));
        assertThat(resendResponse(user.getEmail())).isEqualTo(resendResponse(UUID.randomUUID() + "@example.com"));
        assertThat(latest(user).getId()).isEqualTo(previousId);
        assertThat(latest(user).getIsUsed()).isFalse();
    }

    @Test
    void concurrentVerificationConsumesOtpExactlyOnce() throws Exception {
        User user = register();
        String otp = lastOtp();
        Callable<Boolean> verify = () -> {
            try {
                auth.verifyEmail(new VerifyEmailRequest(user.getEmail(), otp));
                return true;
            } catch (AppException exception) {
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_OTP);
                return false;
            }
        };
        assertThat(concurrently(verify, verify)).containsExactlyInAnyOrder(true, false);
        assertThat(users.findById(user.getId()).orElseThrow().getEmailVerified()).isTrue();
    }

    @Test
    void concurrentResendsSendOnlyOneNewCode() throws Exception {
        User user = register();
        allowResend(user);
        Callable<Boolean> resend = () -> {
            auth.resendOtp(new ResendOtpRequest(user.getEmail()));
            return true;
        };
        concurrently(resend, resend);
        verify(mailSender, times(2)).send(any(MimeMessage.class));
        assertThat(jdbc.queryForObject("select count(*) from otp_tokens where user_id = ? and not is_used", Long.class, user.getId())).isEqualTo(1);
    }

    @Test
    void concurrentResendAndVerificationLeaveConsistentState() throws Exception {
        User user = register();
        String oldOtp = lastOtp();
        allowResend(user);
        var result = concurrently(() -> {
            try {
                auth.verifyEmail(new VerifyEmailRequest(user.getEmail(), oldOtp));
                return true;
            } catch (AppException exception) {
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_OTP);
                return false;
            }
        }, () -> {
            auth.resendOtp(new ResendOtpRequest(user.getEmail()));
            return true;
        });
        boolean verified = users.findById(user.getId()).orElseThrow().getEmailVerified();
        assertThat(verified).isEqualTo(result.getFirst());
        Long active = jdbc.queryForObject("select count(*) from otp_tokens where user_id = ? and not is_used", Long.class, user.getId());
        assertThat(active).isEqualTo(verified ? 0 : 1);
    }

    private List<Boolean> concurrently(Callable<Boolean> first, Callable<Boolean> second) throws Exception {
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(action -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start timed out");
                return action.call();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void publicEndpointsValidateRequestsAndCompleteRegistrationFlow() throws Exception {
        mvc.perform(post("/api/auth/verify-email").contentType("application/json")
                        .content("{\"email\":\"valid@example.com\",\"otp\":\"12x456\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(post("/api/auth/resend-otp").contentType("application/json").content("{\"email\":\"invalid\"}"))
                .andExpect(status().isBadRequest());
        User user = register();
        mvc.perform(post("/api/auth/verify-email").contentType("application/json")
                        .content(json.writeValueAsString(new VerifyEmailRequest(user.getEmail(), lastOtp()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("Email verified successfully. You can now log in"))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());
    }

    private void stubGoogle(String email, String subject) throws Exception {
        var payload = new GoogleIdToken.Payload().setSubject(subject).setEmail(email).setEmailVerified(true);
        payload.set("name", "Google test");
        GoogleIdToken token = mock(GoogleIdToken.class);
        when(token.getPayload()).thenReturn(payload);
        when(googleVerifier.verify("test-google-token")).thenReturn(token);
    }

    @Test
    void googleNeverLinksByEmailButNewAndReturningGoogleUsersCanLogin() throws Exception {
        User passwordUser = register();
        stubGoogle(passwordUser.getEmail(), "google-" + UUID.randomUUID());
        assertThatThrownBy(() -> auth.loginWithGoogle(new GoogleLoginRequest("test-google-token")))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.GOOGLE_ACCOUNT_CONFLICT);
        assertThat(providers.findByUserIdAndProvider(passwordUser.getId(), AuthProviderType.GOOGLE)).isEmpty();
        auth.verifyEmail(new VerifyEmailRequest(passwordUser.getEmail(), lastOtp()));
        assertThatThrownBy(() -> auth.loginWithGoogle(new GoogleLoginRequest("test-google-token")))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.GOOGLE_ACCOUNT_CONFLICT);
        String email = UUID.randomUUID() + "@example.com";
        stubGoogle(email, "google-" + UUID.randomUUID());
        assertThat(auth.loginWithGoogle(new GoogleLoginRequest("test-google-token")).accessToken()).isNotBlank();
        User googleUser = users.findByEmailIgnoreCase(email).orElseThrow();
        createdUsers.add(googleUser.getId());
        assertThat(googleUser.getEmailVerified()).isTrue();
        assertThat(auth.loginWithGoogle(new GoogleLoginRequest("test-google-token")).accessToken()).isNotBlank();
    }

    @Test
    void unverifiedUserCannotRefreshOrUsePreviouslyIssuedAccessToken() throws Exception {
        User user = register();
        auth.verifyEmail(new VerifyEmailRequest(user.getEmail(), lastOtp()));
        var login = auth.login(new LoginRequest(user.getEmail(), PASSWORD));
        user = users.findById(user.getId()).orElseThrow();
        user.setEmailVerified(false);
        users.saveAndFlush(user);
        assertThatThrownBy(() -> auth.refreshToken(new RefreshTokenRequest(login.refreshToken())))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);
        mvc.perform(post("/api/meal/create").header("Authorization", "Bearer " + login.accessToken())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
