package com.tridung.caloriesdetect.controller;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.tridung.caloriesdetect.common.enums.MealType;
import com.tridung.caloriesdetect.common.enums.UserRole;
import com.tridung.caloriesdetect.common.enums.UserStatus;
import com.tridung.caloriesdetect.entity.Meal;
import com.tridung.caloriesdetect.entity.User;
import com.tridung.caloriesdetect.repository.MealRepository;
import com.tridung.caloriesdetect.repository.UserRepository;
import com.tridung.caloriesdetect.security.CustomUserDetails;
import com.tridung.caloriesdetect.security.JwtService;
import com.tridung.caloriesdetect.service.impl.CloudinaryImageStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.health.mail.enabled=false",
        "app.cloudinary.cloud-name=test-cloud", "app.cloudinary.api-key=test-key", "app.cloudinary.api-secret=test-secret"
})
@AutoConfigureMockMvc
class ImageIntegrationTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/Z1sAAAAASUVORK5CYII=");
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired MealRepository meals;
    @Autowired JwtService jwt;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;
    @MockitoBean Cloudinary cloudinary;
    @MockitoBean JavaMailSender mailSender;
    private Uploader uploader;
    private User owner;
    private Meal meal;
    private String authorization;
    private final List<Long> createdUsers = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        uploader = mock(Uploader.class);
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(), anyMap())).thenAnswer(invocation -> {
            Map<?, ?> options = invocation.getArgument(1);
            String id = (String) options.get("public_id");
            return Map.of("public_id", id, "secure_url", url(id));
        });
        when(uploader.destroy(anyString(), anyMap())).thenReturn(Map.of("result", "ok"));
        owner = createUser();
        authorization = token(owner);
        meal = meals.saveAndFlush(Meal.builder().userId(owner).mealType(MealType.LUNCH)
                .mealDate(LocalDate.of(2026, 9, 27)).build());
    }

    @AfterEach void cleanup() {
        createdUsers.forEach(users::deleteById);
    }

    private User createUser() {
        User user = users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@example.com")
                .fullName("Image test").role(UserRole.USER).status(UserStatus.ACTIVE).emailVerified(true).build());
        createdUsers.add(user.getId());
        return user;
    }

    private String token(User user) { return "Bearer " + jwt.generateToken(new CustomUserDetails(user)); }
    private String path(boolean avatar) { return avatar ? "/api/users/me/avatar" : "/api/meals/" + meal.getId() + "/image"; }
    private String field(boolean avatar) { return avatar ? "avatarUrl" : "imageUrl"; }
    private String url(String id) { return "https://res.cloudinary.com/test-cloud/image/upload/" + id + ".png"; }
    private MockMultipartFile file() { return new MockMultipartFile("file", "../../original.png", "image/png", PNG); }
    private String storedId(boolean avatar) {
        return avatar ? users.findById(owner.getId()).orElseThrow().getAvatarPublicId()
                : meals.findById(meal.getId()).orElseThrow().getImagePublicId();
    }
    private String storedUrl(boolean avatar) {
        return avatar ? users.findById(owner.getId()).orElseThrow().getAvatarUrl()
                : meals.findById(meal.getId()).orElseThrow().getImageUrl();
    }
    private void seedImage(boolean avatar) {
        if (avatar) {
            owner.setAvatarPublicId("old-image");
            owner.setAvatarUrl(url("old-image"));
            users.saveAndFlush(owner);
        } else {
            meal.setImagePublicId("old-image");
            meal.setImageUrl(url("old-image"));
            meals.saveAndFlush(meal);
        }
    }
    private void upload(boolean avatar) throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, path(avatar)).file(file()).header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data." + field(avatar)).isString())
                .andExpect(jsonPath("$.data." + (avatar ? "avatarPublicId" : "imagePublicId")).doesNotExist());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void uploadsPersistsAndReturnsImageInExistingResponses(boolean avatar) throws Exception {
        upload(avatar);
        String id = storedId(avatar);
        String prefix = avatar ? "calories-detect/avatars/" : "calories-detect/meals/" + owner.getId() + "/" + meal.getId() + "/";
        assertThat(id).startsWith(prefix).doesNotContain("original");
        assertThat(UUID.fromString(id.substring(prefix.length()))).isNotNull();
        assertThat(storedUrl(avatar)).isEqualTo(url(id));
        mvc.perform(get(avatar ? "/api/user/" + owner.getId() : "/api/meal/" + meal.getId())
                        .header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data." + field(avatar)).value(url(id)));
        verify(uploader).upload(eq(PNG), argThat(options -> "image".equals(options.get("resource_type"))
                && prefix.substring(0, prefix.length() - 1).equals(options.get("asset_folder"))
                && Boolean.FALSE.equals(options.get("overwrite"))
                && List.of("jpg", "png", "webp").equals(options.get("allowed_formats"))));
        verify(uploader, never()).destroy(anyString(), anyMap());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void replacesImageWithUniqueIdAndDeletesPreviousAsset(boolean avatar) throws Exception {
        upload(avatar);
        String oldId = storedId(avatar);
        upload(avatar);
        assertThat(storedId(avatar)).isNotEqualTo(oldId);
        verify(uploader).destroy(eq(oldId), argThat(options -> Boolean.TRUE.equals(options.get("invalidate"))
                && "image".equals(options.get("resource_type"))));
    }

    @Test void mealImageUsesExactProviderPublicIdForPersistenceReplacementAndDeletion() throws Exception {
        String firstId = "provider-returned/first-image";
        String secondId = "provider-returned/second-image";
        doReturn(Map.of("public_id", firstId, "secure_url", url(firstId)),
                Map.of("public_id", secondId, "secure_url", url(secondId)))
                .when(uploader).upload(any(), anyMap());

        upload(false);
        assertThat(storedId(false)).isEqualTo(firstId);
        assertThat(storedUrl(false)).isEqualTo(url(firstId));

        upload(false);
        assertThat(storedId(false)).isEqualTo(secondId);
        assertThat(storedUrl(false)).isEqualTo(url(secondId));
        verify(uploader).destroy(eq(firstId), anyMap());

        mvc.perform(delete(path(false)).header("Authorization", authorization)).andExpect(status().isOk());
        assertThat(storedId(false)).isNull();
        verify(uploader).destroy(eq(secondId), anyMap());
        verify(uploader, times(2)).destroy(anyString(), anyMap());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void deleteClearsBothFieldsAndIsIdempotent(boolean avatar) throws Exception {
        seedImage(avatar);
        for (int i = 0; i < 2; i++) {
            mvc.perform(delete(path(avatar)).header("Authorization", authorization))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data." + field(avatar)).isEmpty());
            assertThat(storedId(avatar)).isNull();
            assertThat(storedUrl(avatar)).isNull();
        }
        verify(uploader, times(1)).destroy(eq("old-image"), anyMap());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void alreadyMissingCloudinaryAssetCanBeDeleted(boolean avatar) throws Exception {
        seedImage(avatar);
        when(uploader.destroy(eq("old-image"), anyMap())).thenReturn(Map.of("result", "not found"));
        mvc.perform(delete(path(avatar)).header("Authorization", authorization)).andExpect(status().isOk());
        assertThat(storedId(avatar)).isNull();
    }

    @Test void deletingMealAlsoDeletesItsImage() throws Exception {
        seedImage(false);
        mvc.perform(delete("/api/meal/" + meal.getId()).header("Authorization", authorization))
                .andExpect(status().isOk());
        assertThat(meals.existsById(meal.getId())).isFalse();
        verify(uploader).destroy(eq("old-image"), anyMap());
    }

    @Test void deletingMealRollsBackWhenImageCannotBeDeleted() throws Exception {
        seedImage(false);
        when(uploader.destroy(eq("old-image"), anyMap())).thenThrow(new IOException("Cloudinary unavailable"));
        mvc.perform(delete("/api/meal/" + meal.getId()).header("Authorization", authorization))
                .andExpect(status().isServiceUnavailable());
        assertThat(storedId(false)).isEqualTo("old-image");
    }

    @Test void updatingMealPreservesImage() throws Exception {
        seedImage(false);
        mvc.perform(put("/api/meal/" + meal.getId()).header("Authorization", authorization)
                        .contentType("application/json").content("{\"mealType\":\"DINNER\",\"mealDate\":\"2026-09-27\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.imageUrl").value(url("old-image")));
        assertThat(storedId(false)).isEqualTo("old-image");
        verifyNoInteractions(uploader);
    }

    @Test void otherUserCannotUploadOrDeleteMealImage() throws Exception {
        String otherToken = token(createUser());
        seedImage(false);
        for (String route : List.of(path(false), "/api/meals/9223372036854775807/image")) {
            mvc.perform(multipart(HttpMethod.PUT, route).file(file()).header("Authorization", otherToken))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(14000));
            mvc.perform(delete(route).header("Authorization", otherToken))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(14000));
        }
        assertThat(storedId(false)).isEqualTo("old-image");
        verifyNoInteractions(uploader);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void endpointsRequireAuthentication(boolean avatar) throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, path(avatar)).file(file())).andExpect(status().isUnauthorized());
        mvc.perform(delete(path(avatar))).andExpect(status().isUnauthorized());
        verifyNoInteractions(uploader);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void rejectsInvalidEmptyAndSpoofedFiles(boolean avatar) throws Exception {
        seedImage(avatar);
        for (MockMultipartFile invalid : List.of(
                new MockMultipartFile("file", "image.svg", "image/svg+xml", "<svg/>".getBytes()),
                new MockMultipartFile("file", "image.png", "image/png", "not an image".getBytes()),
                new MockMultipartFile("file", "image.jpg", "image/jpeg", PNG),
                new MockMultipartFile("file", "image.png", "application/octet-stream", PNG),
                new MockMultipartFile("file", "image.png", "image/png", new byte[0]))) {
            mvc.perform(multipart(HttpMethod.PUT, path(avatar)).file(invalid).header("Authorization", authorization))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(15000));
        }
        mvc.perform(multipart(HttpMethod.PUT, path(avatar)).header("Authorization", authorization))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(15000));
        assertThat(storedId(avatar)).isEqualTo("old-image");
        verifyNoInteractions(uploader);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void rejectsOversizedImage(boolean avatar) throws Exception {
        var oversized = new MockMultipartFile("file", "image.png", "image/png",
                new byte[(int) CloudinaryImageStorageService.MAX_IMAGE_SIZE + 1]);
        mvc.perform(multipart(HttpMethod.PUT, path(avatar)).file(oversized).header("Authorization", authorization))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value(15001));
        verifyNoInteractions(uploader);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void uploadFailureKeepsPreviousImage(boolean avatar) throws Exception {
        seedImage(avatar);
        doThrow(new IOException("Cloudinary unavailable")).when(uploader).upload(any(), anyMap());
        mvc.perform(multipart(HttpMethod.PUT, path(avatar)).file(file()).header("Authorization", authorization))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(15002));
        assertThat(storedId(avatar)).isEqualTo("old-image");
        assertThat(storedUrl(avatar)).isEqualTo(url("old-image"));
        verify(uploader, never()).destroy(eq("old-image"), anyMap());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void deleteFailureRollsBackDatabaseFields(boolean avatar) throws Exception {
        seedImage(avatar);
        when(uploader.destroy(eq("old-image"), anyMap())).thenThrow(new IOException("Cloudinary unavailable"));
        mvc.perform(delete(path(avatar)).header("Authorization", authorization))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(15003));
        assertThat(storedId(avatar)).isEqualTo("old-image");
        assertThat(storedUrl(avatar)).isEqualTo(url("old-image"));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void replacementDeleteFailureRollsBackAndCleansUpNewUpload(boolean avatar) throws Exception {
        seedImage(avatar);
        when(uploader.destroy(eq("old-image"), anyMap())).thenReturn(Map.of("result", "error"));
        mvc.perform(multipart(HttpMethod.PUT, path(avatar)).file(file()).header("Authorization", authorization))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(15003));
        assertThat(storedId(avatar)).isEqualTo("old-image");
        assertThat(storedUrl(avatar)).isEqualTo(url("old-image"));
        verify(uploader).destroy(startsWith("calories-detect/"), anyMap());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void databaseFailureCleansUpUploadWithoutDeletingPreviousAsset(boolean avatar) throws Exception {
        seedImage(avatar);
        String invalidId = "x".repeat(256);
        doReturn(Map.of("public_id", invalidId, "secure_url", url(invalidId))).when(uploader).upload(any(), anyMap());
        assertThatThrownBy(() -> mvc.perform(multipart(HttpMethod.PUT, path(avatar)).file(file())
                .header("Authorization", authorization))).hasRootCauseMessage("ERROR: value too long for type character varying(255)");
        assertThat(storedId(avatar)).isEqualTo("old-image");
        verify(uploader).destroy(eq(invalidId), anyMap());
        verify(uploader, never()).destroy(eq("old-image"), anyMap());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void concurrentReplacementsLeaveOneImage(boolean avatar) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); upload(avatar); return null; });
            var second = pool.submit(() -> { start.await(); upload(avatar); return null; });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        String remainingId = storedId(avatar);
        verify(uploader, times(2)).upload(any(), anyMap());
        verify(uploader, times(1)).destroy(argThat(id -> !remainingId.equals(id)), anyMap());
        verify(uploader, never()).destroy(eq(remainingId), anyMap());
    }

    @Test void imageMigrationPreservesExistingUsersAndMeals() {
        String schema = "image_migration_" + UUID.randomUUID().toString().replace("-", "");
        var previous = org.flywaydb.core.Flyway.configure().dataSource(dataSource)
                .schemas(schema).defaultSchema(schema).target("7").load();
        var upgraded = org.flywaydb.core.Flyway.configure().dataSource(dataSource)
                .schemas(schema).defaultSchema(schema).target("8").cleanDisabled(false).load();
        try {
            previous.migrate();
            jdbc.update("insert into " + schema + ".users(id, email, full_name, role, status, email_verified, created_at, updated_at) "
                    + "values (1, 'existing@example.com', 'Existing user', 'USER', 'ACTIVE', true, now(), now())");
            jdbc.update("insert into " + schema + ".meals(id, user_id, meal_type, meal_date, created_at, updated_at) "
                    + "values (1, 1, 'LUNCH', '2026-09-27', now(), now())");
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(jdbc.queryForObject("select email from " + schema + ".users where id = 1", String.class))
                    .isEqualTo("existing@example.com");
            assertThat(jdbc.queryForObject("select avatar_public_id is null and avatar_url is null from " + schema + ".users where id = 1", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("select meal_type from " + schema + ".meals where id = 1", String.class)).isEqualTo("LUNCH");
            assertThat(jdbc.queryForObject("select image_public_id is null and image_url is null from " + schema + ".meals where id = 1", Boolean.class)).isTrue();
            assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_schema = ? and table_name = 'meal_items'",
                    String.class, schema)).doesNotContain("image_public_id", "image_url");
        } finally {
            upgraded.clean();
        }
    }

    @ParameterizedTest @ValueSource(ints = {3, 5})
    void servletAcceptsImagesWithinFiveMiBLimit(int sizeMiB) throws Exception {
        String boundary = "image-test-boundary";
        byte[] image = java.util.Arrays.copyOf(PNG, sizeMiB * 1024 * 1024);
        byte[] header = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"image.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] footer = ("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (HttpClient client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path(true)))
                    .header("Authorization", authorization)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .PUT(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(header),
                            HttpRequest.BodyPublishers.ofByteArray(image), HttpRequest.BodyPublishers.ofByteArray(footer)))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"success\":true", "\"avatarUrl\":");
        }
        verify(uploader).upload(argThat((byte[] bytes) -> bytes.length == image.length), anyMap());
    }

    @Test void servletRejectsOversizedMultipartWithProjectErrorEnvelope() throws Exception {
        String boundary = "image-test-boundary";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"large.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n" + "x".repeat(6 * 1024 * 1024) + "\r\n--" + boundary + "--\r\n";
        try (HttpClient client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path(true)))
                    .header("Authorization", authorization)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(413);
            assertThat(response.body()).contains("\"code\":15001", "\"success\":false");
        }
        verifyNoInteractions(uploader);
    }
}
