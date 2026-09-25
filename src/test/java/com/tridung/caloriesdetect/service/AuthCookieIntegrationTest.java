package com.tridung.caloriesdetect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tridung.caloriesdetect.common.enums.*;
import com.tridung.caloriesdetect.dto.request.auth.RefreshTokenRequest;
import com.tridung.caloriesdetect.entity.*;
import com.tridung.caloriesdetect.exception.AppException;
import com.tridung.caloriesdetect.repository.*;
import com.tridung.caloriesdetect.security.JwtService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"management.health.mail.enabled=false", "app.auth.secure-cookies=false"})
@AutoConfigureMockMvc
class AuthCookieIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AuthProviderRepository providers;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @MockitoBean JavaMailSender mailSender;
    private User user;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void createAccount() {
        user = users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@example.com")
                .fullName("Cookie test").emailVerified(true).status(UserStatus.ACTIVE).role(UserRole.USER).build());
        providers.saveAndFlush(AuthProvider.builder().user(user).provider(AuthProviderType.LOCAL)
                .passwordHash(encoder.encode("Password123!")).build());
    }
    @AfterEach void cleanup() { users.deleteById(user.getId()); }

    private Cookie login() throws Exception {
        var response = mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                        .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(cookie().httpOnly("calories_refresh", true))
                .andExpect(cookie().secure("calories_refresh", false))
                .andReturn().getResponse();
        return response.getCookie("calories_refresh");
    }

    @Test void loginReloadRotationReplayAndLogout() throws Exception {
        Cookie original = login();
        var refresh = mvc.perform(post("/api/auth/refresh-token").with(csrf()).cookie(original))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn().getResponse();
        String access = json.readTree(refresh.getContentAsString()).path("data").path("accessToken").asText();
        Cookie rotated = refresh.getCookie("calories_refresh");
        assertThat(rotated.getValue()).isNotEqualTo(original.getValue());
        assertThat(jdbc.queryForObject("select revoked_at is not null from refresh_tokens where token_hash=?",
                Boolean.class, jwt.hashToken(original.getValue()))).isTrue();
        mvc.perform(get("/api/user/" + user.getId()).header("Authorization", "Bearer " + access)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/refresh-token").with(csrf()).cookie(original)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout").with(csrf()).cookie(rotated))
                .andExpect(status().isOk()).andExpect(cookie().maxAge("calories_refresh", 0));
        mvc.perform(post("/api/auth/logout").with(csrf()).cookie(rotated)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/refresh-token").with(csrf()).cookie(rotated)).andExpect(status().isUnauthorized());
    }

    @Test void logoutWithPreRotationCookieRevokesTheSuccessor() throws Exception {
        Cookie original = login();
        Cookie rotated = mvc.perform(post("/api/auth/refresh-token").with(csrf()).cookie(original))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("calories_refresh");
        mvc.perform(post("/api/auth/logout").with(csrf()).cookie(original)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/refresh-token").with(csrf()).cookie(rotated)).andExpect(status().isUnauthorized());
    }

    @Test void expiredRefreshIsRejectedButLogoutStillClearsIt() throws Exception {
        Cookie cookie = login();
        jdbc.update("update refresh_tokens set expires_at=current_timestamp - interval '1 minute' where token_hash=?", jwt.hashToken(cookie.getValue()));
        mvc.perform(post("/api/auth/refresh-token").with(csrf()).cookie(cookie)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(13001));
        mvc.perform(post("/api/auth/logout").with(csrf()).cookie(cookie)).andExpect(status().isOk())
                .andExpect(cookie().maxAge("calories_refresh", 0));
    }

    @Test void concurrentRefreshHasExactlyOneWinner() throws Exception {
        Cookie cookie = login();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> refresh = () -> {
            start.await(5, TimeUnit.SECONDS);
            try { auth.refreshToken(new RefreshTokenRequest(cookie.getValue())); return true; }
            catch (AppException exception) { return false; }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(refresh);
            var second = pool.submit(refresh);
            start.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id=? and revoked_at is null", Long.class, user.getId())).isEqualTo(1);
    }
}
