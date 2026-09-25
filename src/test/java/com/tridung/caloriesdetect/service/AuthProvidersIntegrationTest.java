package com.tridung.caloriesdetect.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.tridung.caloriesdetect.common.enums.AuthProviderType;
import com.tridung.caloriesdetect.common.enums.UserRole;
import com.tridung.caloriesdetect.common.enums.UserStatus;
import com.tridung.caloriesdetect.dto.request.auth.*;
import com.tridung.caloriesdetect.entity.AuthProvider;
import com.tridung.caloriesdetect.entity.User;
import com.tridung.caloriesdetect.exception.AppException;
import com.tridung.caloriesdetect.exception.ErrorCode;
import com.tridung.caloriesdetect.repository.AuthProviderRepository;
import com.tridung.caloriesdetect.repository.UserRepository;
import com.tridung.caloriesdetect.security.CustomUserDetails;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "management.health.mail.enabled=false")
@AutoConfigureMockMvc
class AuthProvidersIntegrationTest {
    @Autowired AuthService auth;
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired AuthProviderRepository providers;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired MockMvc mvc;
    @MockitoBean GoogleIdTokenVerifier googleVerifier;
    @MockitoBean JavaMailSender mailSender;

    private final List<Long> createdUsers = new ArrayList<>();
    private static final String PASSWORD = "TestPassword123!";

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        createdUsers.forEach(users::deleteById);
    }

    private User localUser() {
        User user = users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@example.com")
                .fullName("Provider test").role(UserRole.USER).status(UserStatus.ACTIVE).emailVerified(true).build());
        createdUsers.add(user.getId());
        providers.saveAndFlush(AuthProvider.builder().user(user).provider(AuthProviderType.LOCAL)
                .passwordHash(encoder.encode(PASSWORD)).build());
        return user;
    }

    private void googleToken(String email, String subject) throws Exception {
        var payload = new GoogleIdToken.Payload().setSubject(subject).setEmail(email).setEmailVerified(true);
        var token = mock(GoogleIdToken.class);
        when(token.getPayload()).thenReturn(payload);
        when(googleVerifier.verify("google-token")).thenReturn(token);
    }

    private User googleUser() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        googleToken(email, UUID.randomUUID().toString());
        auth.loginWithGoogle(new GoogleLoginRequest("google-token"));
        User user = users.findByEmailIgnoreCase(email).orElseThrow();
        createdUsers.add(user.getId());
        return user;
    }

    private void as(User user, Runnable action) {
        var principal = new CustomUserDetails(user);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        try { action.run(); } finally { SecurityContextHolder.clearContext(); }
    }

    private void assertError(ThrowingCallable action, ErrorCode error) {
        assertThatThrownBy(action).isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(error);
    }

    @Test
    void adminCreationAlsoStoresCredentialsInLocalProvider() {
        String email = UUID.randomUUID() + "@example.com";
        var response = userService.createOneUser(new com.tridung.caloriesdetect.dto.request.admin.AdminUserRequest(
                email, "Admin-created user", PASSWORD, UserRole.USER));
        createdUsers.add(response.id());
        var provider = providers.findByUserIdAndProvider(response.id(), AuthProviderType.LOCAL).orElseThrow();
        assertThat(encoder.matches(PASSWORD, provider.getPasswordHash())).isTrue();
        assertThat(provider.getProviderSubject()).isNull();
    }

    @Test
    void explicitLinkPreservesLocalPasswordAndBothLoginsResolveToSameUser() throws Exception {
        User user = localUser();
        String hash = providers.findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL).orElseThrow().getPasswordHash();
        String subject = UUID.randomUUID().toString();
        googleToken(user.getEmail(), subject);
        assertError(() -> auth.loginWithGoogle(new GoogleLoginRequest("google-token")), ErrorCode.GOOGLE_ACCOUNT_CONFLICT);
        var login = auth.login(new LoginRequest(user.getEmail(), PASSWORD));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/auth/google/link").header("Authorization", "Bearer " + login.accessToken())
                            .contentType("application/json").content("{\"idToken\":\"google-token\"}"))
                    .andExpect(status().isOk());
        }
        assertThat(providers.findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL).orElseThrow().getPasswordHash()).isEqualTo(hash);
        assertThat(providers.findByUserIdAndProvider(user.getId(), AuthProviderType.GOOGLE).orElseThrow().getProviderSubject()).isEqualTo(subject);
        assertThat(auth.loginWithGoogle(new GoogleLoginRequest("google-token")).accessToken()).isNotBlank();
        assertThat(auth.login(new LoginRequest(user.getEmail(), PASSWORD)).accessToken()).isNotBlank();
        assertThat(jdbc.queryForObject("select count(*) from auth_providers where user_id = ?", Long.class, user.getId())).isEqualTo(2);
    }

    @Test
    void googleOnlyUserCanSetPasswordUsingJwtAndRetainsGoogleLogin() throws Exception {
        User user = googleUser();
        assertThat(providers.findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL)).isEmpty();
        assertError(() -> auth.login(new LoginRequest(user.getEmail(), PASSWORD)), ErrorCode.INVALID_CREDENTIALS);
        var googleLogin = auth.loginWithGoogle(new GoogleLoginRequest("google-token"));
        mvc.perform(post("/api/auth/set-password").header("Authorization", "Bearer " + googleLogin.accessToken())
                        .contentType("application/json")
                        .content("{\"newPassword\":\"" + PASSWORD + "\",\"confirmPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
        assertThat(encoder.matches(PASSWORD, providers.findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL)
                .orElseThrow().getPasswordHash())).isTrue();
        assertError(() -> auth.refreshToken(new RefreshTokenRequest(googleLogin.refreshToken())), ErrorCode.REFRESH_TOKEN_REVOKED);
        assertThat(auth.login(new LoginRequest(user.getEmail(), PASSWORD)).accessToken()).isNotBlank();
        assertThat(auth.loginWithGoogle(new GoogleLoginRequest("google-token")).accessToken()).isNotBlank();
        assertError(() -> as(user, () -> auth.setPassword(new SetPasswordRequest(PASSWORD, PASSWORD))), ErrorCode.LOCAL_PASSWORD_ALREADY_SET);
    }

    @Test
    void setPasswordRejectsInvalidInputWithoutCreatingProvider() throws Exception {
        User user = googleUser();
        assertError(() -> as(user, () -> auth.setPassword(new SetPasswordRequest(PASSWORD, "different"))), ErrorCode.PASSWORD_CONFIRM_NOT_MATCH);
        for (String password : List.of("short", "x".repeat(73), "é".repeat(37), " ".repeat(8))) {
            assertError(() -> as(user, () -> auth.setPassword(new SetPasswordRequest(password, password))), ErrorCode.INVALID_NEW_PASSWORD);
        }
        assertThat(providers.findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL)).isEmpty();
    }

    @Test
    void linkingRejectsEmailMismatchSubjectOwnedByAnotherUserAndReplacingExistingSubject() throws Exception {
        User user = localUser();
        User other = googleUser();
        String subject = providers.findByUserIdAndProvider(other.getId(), AuthProviderType.GOOGLE).orElseThrow().getProviderSubject();
        assertError(() -> as(user, () -> auth.linkGoogle(new GoogleLoginRequest("google-token"))), ErrorCode.GOOGLE_ACCOUNT_CONFLICT);
        googleToken(user.getEmail(), subject);
        assertError(() -> as(user, () -> auth.linkGoogle(new GoogleLoginRequest("google-token"))), ErrorCode.GOOGLE_ACCOUNT_CONFLICT);
        googleToken(user.getEmail(), UUID.randomUUID().toString());
        as(user, () -> auth.linkGoogle(new GoogleLoginRequest("google-token")));
        googleToken(user.getEmail(), UUID.randomUUID().toString());
        assertError(() -> as(user, () -> auth.linkGoogle(new GoogleLoginRequest("google-token"))), ErrorCode.GOOGLE_ACCOUNT_CONFLICT);
    }

    @Test
    void newEndpointsRequireAuthenticationAndVerifiedActiveUser() throws Exception {
        for (String path : List.of("/api/auth/google/link", "/api/auth/set-password")) {
            mvc.perform(post(path).contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
        }
        User user = googleUser();
        for (boolean active : List.of(true, false)) {
            user.setEmailVerified(!active);
            user.setStatus(active ? UserStatus.ACTIVE : UserStatus.INACTIVE);
            users.saveAndFlush(user);
            ErrorCode error = active ? ErrorCode.EMAIL_NOT_VERIFIED : ErrorCode.ACCOUNT_INACTIVE;
            assertError(() -> as(user, () -> auth.setPassword(new SetPasswordRequest(PASSWORD, PASSWORD))), error);
            assertError(() -> as(user, () -> auth.linkGoogle(new GoogleLoginRequest("google-token"))), error);
        }
    }

    @Test
    void invalidGoogleTokenCannotCreateOrLinkProvider() throws Exception {
        User user = localUser();
        googleToken(user.getEmail(), " ");
        assertError(() -> as(user, () -> auth.linkGoogle(new GoogleLoginRequest("google-token"))), ErrorCode.INVALID_GOOGLE_TOKEN);
        assertError(() -> auth.loginWithGoogle(new GoogleLoginRequest("google-token")), ErrorCode.INVALID_GOOGLE_TOKEN);
        when(googleVerifier.verify("google-token")).thenReturn(null);
        assertError(() -> as(user, () -> auth.linkGoogle(new GoogleLoginRequest("google-token"))), ErrorCode.INVALID_GOOGLE_TOKEN);
        assertThat(providers.findByUserIdAndProvider(user.getId(), AuthProviderType.GOOGLE)).isEmpty();
    }

    @Test
    void emptyProductionSchemaAtV5UpgradesWithoutLegacyCredentials() {
        String schema = "auth_migration_" + UUID.randomUUID().toString().replace("-", "");
        var current = org.flywaydb.core.Flyway.configure().dataSource(dataSource)
                .schemas(schema).defaultSchema(schema).target("5").load();
        var upgraded = org.flywaydb.core.Flyway.configure().dataSource(dataSource)
                .schemas(schema).defaultSchema(schema).cleanDisabled(false).load();
        try {
            current.migrate();
            assertThat(current.info().current().getVersion().getVersion()).isEqualTo("5");
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(2);
            assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("7");
            assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_schema = ? and table_name = 'users'",
                    String.class, schema)).doesNotContain("password", "google_subject", "local_password_enabled");
            assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_schema = ? and table_name = 'auth_providers'",
                    String.class, schema)).containsExactlyInAnyOrder("id", "user_id", "provider", "provider_subject", "password_hash", "created_at", "updated_at");
            assertThat(jdbc.queryForList("select data_type from information_schema.columns where table_schema = ? and table_name = 'auth_providers' and column_name in ('created_at', 'updated_at')",
                    String.class, schema)).containsExactly("timestamp with time zone", "timestamp with time zone");
        } finally {
            upgraded.clean();
        }
    }

    @Test
    void populatedLegacySchemaStopsBeforeDroppingCredentials() {
        String schema = "auth_migration_" + UUID.randomUUID().toString().replace("-", "");
        var legacy = org.flywaydb.core.Flyway.configure().dataSource(dataSource)
                .schemas(schema).defaultSchema(schema).target("5").load();
        var upgraded = org.flywaydb.core.Flyway.configure().dataSource(dataSource)
                .schemas(schema).defaultSchema(schema).cleanDisabled(false).load();
        try {
            legacy.migrate();
            jdbc.update("insert into " + schema + ".users(email, password, full_name, role, status, created_at, updated_at) "
                    + "values ('legacy@example.com', 'preserved-hash', 'Legacy', 'USER', 'ACTIVE', now(), now())");
            assertThatThrownBy(upgraded::migrate)
                    .hasStackTraceContaining("requires an empty users table");
            assertThat(jdbc.queryForObject("select password from " + schema + ".users where email='legacy@example.com'", String.class))
                    .isEqualTo("preserved-hash");
        } finally {
            upgraded.clean();
        }
    }

    @Test
    void databaseEnforcesBothUniqueConstraintsAndCascadesOnUserDeletion() {
        User user = localUser();
        User other = localUser(); // Multiple LOCAL rows with null subjects are valid.
        assertThatThrownBy(() -> providers.saveAndFlush(AuthProvider.builder().user(user).provider(AuthProviderType.LOCAL)
                .passwordHash("duplicate").build())).isInstanceOf(DataIntegrityViolationException.class);
        String subject = UUID.randomUUID().toString();
        AuthProvider google = providers.saveAndFlush(AuthProvider.builder().user(user).provider(AuthProviderType.GOOGLE)
                .providerSubject(subject).build());
        assertThatThrownBy(() -> providers.saveAndFlush(AuthProvider.builder().user(other).provider(AuthProviderType.GOOGLE)
                .providerSubject(subject).build())).isInstanceOf(DataIntegrityViolationException.class);
        users.deleteById(user.getId());
        createdUsers.remove(user.getId());
        assertThat(providers.findById(google.getId())).isEmpty();
        assertThat(providers.findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL)).isEmpty();
    }

    @Test
    void concurrentSetPasswordCreatesExactlyOneLocalProvider() throws Exception {
        User user = googleUser();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<Boolean> set = () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start timed out");
            try {
                as(user, () -> auth.setPassword(new SetPasswordRequest(PASSWORD, PASSWORD)));
                return true;
            } catch (AppException exception) {
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.LOCAL_PASSWORD_ALREADY_SET);
                return false;
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(set);
            var second = executor.submit(set);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(auth.login(new LoginRequest(user.getEmail(), PASSWORD)).accessToken()).isNotBlank();
    }
}
