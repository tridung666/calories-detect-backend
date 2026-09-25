package com.tridung.caloriesdetect.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tridung.caloriesdetect.common.enums.UserRole;
import com.tridung.caloriesdetect.common.enums.UserStatus;
import com.tridung.caloriesdetect.entity.User;
import com.tridung.caloriesdetect.repository.UserRepository;
import com.tridung.caloriesdetect.security.CustomUserDetails;
import com.tridung.caloriesdetect.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Use a real servlet server: MockMvc does not reproduce sendError's /error dispatch.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.health.mail.enabled=false")
class AuthorizationHttpIntegrationTest {
    @Value("${local.server.port}") int port;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @MockitoBean JavaMailSender mailSender;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private final List<Long> createdUsers = new ArrayList<>();

    private User user(UserRole role) {
        User user = users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@example.com")
                .fullName("Authorization test").role(role).status(UserStatus.ACTIVE)
                .emailVerified(true).build());
        createdUsers.add(user.getId());
        return user;
    }

    private HttpResponse<String> request(String method, String path, User user, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (user != null) builder.header("Authorization", "Bearer " + jwt.generateToken(new CustomUserDetails(user)));
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @AfterEach void cleanup() {
        http.close();
        createdUsers.forEach(users::deleteById);
    }

    @Test void ownerAndAdminCanReadProfileButAnotherUserCannot() throws Exception {
        User owner = user(UserRole.USER);
        User other = user(UserRole.USER);
        User admin = user(UserRole.ADMIN);
        String path = "/api/user/" + owner.getId();
        var own = request("GET", path, owner, null);
        assertThat(own.statusCode()).isEqualTo(200);
        assertThat(json.readTree(own.body()).path("data").path("id").asLong()).isEqualTo(owner.getId());
        var denied = request("GET", path, other, null);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(json.readTree(denied.body()).path("code").asInt()).isEqualTo(403);
        assertThat(denied.body()).doesNotContain(owner.getEmail());
        assertThat(request("GET", path, admin, null).statusCode()).isEqualTo(200);
    }

    @Test void forbiddenAdminOperationsKeep403AndTheUserSessionRemainsValid() throws Exception {
        User member = user(UserRole.USER);
        long count = users.count();
        var list = request("GET", "/api/admin/users?pageNo=0&pageSize=10", member, null);
        assertThat(list.statusCode()).isEqualTo(403);
        assertThat(json.readTree(list.body()).path("code").asInt()).isEqualTo(403);
        var create = request("POST", "/api/admin/users", member, """
                {"email":"forbidden@example.com","fullName":"Forbidden","password":"Password123!","role":"ADMIN"}
                """);
        assertThat(create.statusCode()).isEqualTo(403);
        assertThat(users.count()).isEqualTo(count);
        assertThat(request("GET", "/api/user/" + member.getId(), member, null).statusCode()).isEqualTo(200);
    }

    @Test void adminCanListUsers() throws Exception {
        var response = request("GET", "/api/admin/users?pageNo=0&pageSize=10", user(UserRole.ADMIN), null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("data").path("data").isArray()).isTrue();
    }

    @Test void unauthenticatedProfileRequestReturnsJson401() throws Exception {
        var response = request("GET", "/api/user/1", null, null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(401);
    }
}
