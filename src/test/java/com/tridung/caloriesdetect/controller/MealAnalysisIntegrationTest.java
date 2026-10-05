package com.tridung.caloriesdetect.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tridung.caloriesdetect.common.enums.*;
import com.tridung.caloriesdetect.dto.response.meal.MealAnalysisResponse;
import com.tridung.caloriesdetect.entity.*;
import com.tridung.caloriesdetect.exception.AppException;
import com.tridung.caloriesdetect.exception.ErrorCode;
import com.tridung.caloriesdetect.repository.*;
import com.tridung.caloriesdetect.security.*;
import com.tridung.caloriesdetect.service.MealAnalysisClient;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class MealAnalysisIntegrationTest {
    private static final String IMAGE = "https://res.cloudinary.com/test/image/upload/meal.jpg";
    private static final String CONFIRM = """
            {"items":[{"name":"Chicken breast","quantityGrams":180,"calories":298,
            "protein":55.8,"carbohydrate":0,"fat":6.5}]}
            """;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired MealRepository meals;
    @Autowired JwtService jwt;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoSpyBean MealItemRepository items;
    @MockitoBean MealAnalysisClient ai;
    private final ObjectMapper json = new ObjectMapper();
    private final List<Long> createdUsers = new ArrayList<>();
    private Meal meal;
    private String authorization;

    @BeforeEach void setup() {
        User owner = createUser();
        authorization = token(owner);
        meal = meals.saveAndFlush(Meal.builder().userId(owner).mealType(MealType.LUNCH)
                .mealDate(LocalDate.of(2026, 9, 30)).imageUrl(IMAGE).build());
    }

    @AfterEach void cleanup() {
        reset(items);
        createdUsers.forEach(users::deleteById);
    }

    private User createUser() {
        User user = users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@example.com")
                .fullName("AI meal test").role(UserRole.USER).status(UserStatus.ACTIVE).emailVerified(true).build());
        createdUsers.add(user.getId());
        return user;
    }

    private String token(User user) { return "Bearer " + jwt.generateToken(new CustomUserDetails(user)); }
    private String path(String action) { return "/api/meals/" + meal.getId() + "/" + action; }
    private List<MealItem> savedItems() { return items.findAllByMeal_IdOrderByIdAsc(meal.getId()); }

    private long seedItem() {
        MealItem item = new MealItem();
        item.setMeal(meal);
        item.setInputName("Previous item");
        item.setQuantityGrams(new BigDecimal("100"));
        item.setCalories(new BigDecimal("200"));
        item.setProteinGrams(new BigDecimal("10"));
        item.setCarbohydrateGrams(BigDecimal.ZERO);
        item.setFatGrams(BigDecimal.ZERO);
        return items.saveAndFlush(item).getId();
    }

    @Test void analysisReturnsDraftWithoutWritingOrHoldingDatabaseTransaction() throws Exception {
        long oldId = seedItem();
        when(ai.analyze(meal.getId(), IMAGE)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new MealAnalysisResponse(meal.getId(), List.of(new MealAnalysisResponse.Item("Grilled chicken",
                    new BigDecimal("150"), new BigDecimal("248"), new BigDecimal("46.5"),
                    BigDecimal.ZERO, new BigDecimal("5.4"), new BigDecimal("0.91"))));
        });
        mvc.perform(post(path("analyze")).header("Authorization", authorization)
                        .contentType("application/json").content("{\"mealId\":999,\"imageUrl\":\"https://other/image\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("data.mealId").value(meal.getId()))
                .andExpect(jsonPath("data.items[0].estimatedGrams").value(150))
                .andExpect(jsonPath("data.items[0].protein").value(46.5))
                .andExpect(jsonPath("data.items[0].confidence").value(0.91));
        assertThat(savedItems()).extracting(MealItem::getId).containsExactly(oldId);
        verify(ai).analyze(meal.getId(), IMAGE);
        verify(items, never()).deleteAllByMeal_Id(anyLong());
        verify(items, never()).saveAllAndFlush(any());
    }

    @Test void analysisWithoutExistingItemsAlsoLeavesDatabaseEmpty() throws Exception {
        when(ai.analyze(meal.getId(), IMAGE)).thenReturn(new MealAnalysisResponse(meal.getId(), List.of(
                new MealAnalysisResponse.Item("Chicken", new BigDecimal("150"), new BigDecimal("248"),
                        new BigDecimal("46.5"), BigDecimal.ZERO, new BigDecimal("5.4"), new BigDecimal("0.91")))));
        mvc.perform(post(path("analyze")).header("Authorization", authorization)).andExpect(status().isOk());
        assertThat(savedItems()).isEmpty();
    }

    @Test void confirmsEditedItemsReplacesOldItemsAndPreservesDecimals() throws Exception {
        long oldId = seedItem();
        mvc.perform(post(path("confirm-analysis")).header("Authorization", authorization)
                        .contentType("application/json").content(CONFIRM.replace("{\"items\"", "{\"mealId\":999,\"items\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("data.id").value(meal.getId()))
                .andExpect(jsonPath("data.imageUrl").value(IMAGE))
                .andExpect(jsonPath("data.items.length()").value(1))
                .andExpect(jsonPath("data.items[0].mealId").value(meal.getId()))
                .andExpect(jsonPath("data.items[0].inputName").value("Chicken breast"))
                .andExpect(jsonPath("data.items[0].quantityGrams").value(180))
                .andExpect(jsonPath("data.items[0].proteinGrams").value(55.8))
                .andExpect(jsonPath("data.items[0].fatGrams").value(6.5));
        assertThat(items.findById(oldId)).isEmpty();
        assertThat(savedItems()).hasSize(1);
        assertThat(savedItems().getFirst().getProteinGrams()).isEqualByComparingTo("55.8");
        assertThat(savedItems().getFirst().getFatGrams()).isEqualByComparingTo("6.5");
        mvc.perform(get("/api/meal/{mealId}/items", meal.getId()).header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("data[0].proteinGrams").value(55.8));
        verifyNoInteractions(ai);
        // A second confirmation replaces rather than appends.
        mvc.perform(post(path("confirm-analysis")).header("Authorization", authorization)
                        .contentType("application/json").content(CONFIRM.replace("Chicken breast", "Edited chicken")))
                .andExpect(status().isOk());
        assertThat(savedItems()).extracting(MealItem::getInputName).containsExactly("Edited chicken");
    }

    @Test void invalidConfirmationNeverDeletesExistingItems() throws Exception {
        long oldId = seedItem();
        List<String> invalid = new ArrayList<>(List.of("{}", "{\"items\":null}", "{\"items\":[]}",
                "{\"items\":[null]}", "{\"items\":[{}]}", CONFIRM.replace("Chicken breast", " "),
                CONFIRM.replace("Chicken breast", "x".repeat(256))));
        for (String field : List.of("quantityGrams", "calories", "protein", "carbohydrate", "fat")) {
            for (String value : List.of("null", "-1", "0.001", field.equals("quantityGrams") ? "100000000" : "2147483648")) {
                var body = json.readTree(CONFIRM);
                ((ObjectNode) body.get("items").get(0)).set(field, json.readTree(value));
                invalid.add(body.toString());
            }
        }
        invalid.add(CONFIRM.replace("180", "0"));
        // A valid first item must not be saved when a later item is invalid.
        invalid.add(CONFIRM.replace("}]", "},{}]"));
        for (String body : invalid) {
            mvc.perform(post(path("confirm-analysis")).header("Authorization", authorization)
                            .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        assertThat(savedItems()).extracting(MealItem::getId).containsExactly(oldId);
        verify(items, never()).deleteAllByMeal_Id(anyLong());
    }

    @Test void saveFailureRollsBackDeletion() {
        long oldId = seedItem();
        doThrow(new IllegalStateException("Simulated persistence failure")).when(items).saveAllAndFlush(any());
        assertThatThrownBy(() -> mvc.perform(post(path("confirm-analysis")).header("Authorization", authorization)
                        .contentType("application/json").content(CONFIRM)))
                .hasRootCauseMessage("Simulated persistence failure");
        assertThat(savedItems()).extracting(MealItem::getId).containsExactly(oldId);
    }

    @Test void concurrentConfirmationsLeaveOneCompleteSet() throws Exception {
        String second = CONFIRM.replace("Chicken breast", "Other chicken");
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                mvc.perform(post(path("confirm-analysis")).header("Authorization", authorization)
                        .contentType("application/json").content(CONFIRM)).andExpect(status().isOk());
                return null;
            });
            var other = pool.submit(() -> {
                start.await();
                mvc.perform(post(path("confirm-analysis")).header("Authorization", authorization)
                        .contentType("application/json").content(second)).andExpect(status().isOk());
                return null;
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            other.get(10, TimeUnit.SECONDS);
        }
        assertThat(savedItems()).hasSize(1);
        assertThat(savedItems().getFirst().getInputName()).isIn("Chicken breast", "Other chicken");
    }

    @Test void bothEndpointsRequireAuthenticationAndOwnedMeal() throws Exception {
        String other = token(createUser());
        for (String action : List.of("analyze", "confirm-analysis")) {
            mvc.perform(post(path(action)).contentType("application/json").content(CONFIRM))
                    .andExpect(status().isUnauthorized());
            for (String route : List.of(path(action), "/api/meals/9223372036854775807/" + action)) {
                mvc.perform(post(route).header("Authorization", other).contentType("application/json").content(CONFIRM))
                        .andExpect(status().isNotFound()).andExpect(jsonPath("code").value(14000));
            }
        }
        verifyNoInteractions(ai);
        assertThat(savedItems()).isEmpty();
    }

    @Test void analysisRequiresImage() throws Exception {
        for (String missing : Arrays.asList(null, " ")) {
            meal.setImageUrl(missing);
            meals.saveAndFlush(meal);
            mvc.perform(post(path("analyze")).header("Authorization", authorization))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value(16000));
        }
        verifyNoInteractions(ai);
    }

    @Test void aiErrorsUseProjectEnvelopeAndDoNotChangeItems() throws Exception {
        long oldId = seedItem();
        Map<ErrorCode, Integer> errors = Map.of(ErrorCode.AI_SERVICE_UNAVAILABLE, 503,
                ErrorCode.AI_SERVICE_TIMEOUT, 504, ErrorCode.INVALID_AI_RESPONSE, 502, ErrorCode.AI_EMPTY_RESULT, 422, ErrorCode.AI_IMAGE_UNREADABLE, 422);
        for (var error : errors.entrySet()) {
            doThrow(new AppException(error.getKey())).when(ai).analyze(meal.getId(), IMAGE);
            mvc.perform(post(path("analyze")).header("Authorization", authorization))
                    .andExpect(status().is(error.getValue())).andExpect(jsonPath("success").value(false))
                    .andExpect(jsonPath("code").value(error.getKey().getCode()));
        }
        assertThat(savedItems()).extracting(MealItem::getId).containsExactly(oldId);
    }

    @Test void swaggerDocumentsBothNewEndpointsAndDraftFields() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/meals/{mealId}/analyze'].post").exists())
                .andExpect(jsonPath("$.paths['/api/meals/{mealId}/confirm-analysis'].post.requestBody.required").value(true))
                .andExpect(jsonPath("$.components.schemas.MealAnalysisPredictionItem.properties.estimatedGrams").exists())
                .andExpect(jsonPath("$.components.schemas.MealAnalysisPredictionItem.properties.confidence").exists())
                .andExpect(jsonPath("$.components.schemas.ConfirmedMealAnalysisItem.properties.quantityGrams").exists());
    }

    @Test void nameMigrationRemovesUnusedColumnAndPreservesExistingMealItem() {
        String schema = "item_name_migration_" + UUID.randomUUID().toString().replace("-", "");
        var previous = org.flywaydb.core.Flyway.configure().dataSource(dataSource)
                .schemas(schema).defaultSchema(schema).target("9").load();
        var upgraded = org.flywaydb.core.Flyway.configure().dataSource(dataSource)
                .schemas(schema).defaultSchema(schema).target("10").cleanDisabled(false).load();
        try {
            previous.migrate();
            jdbc.update("insert into " + schema + ".users(id, email, full_name, role, status, email_verified, created_at, updated_at) "
                    + "values (1, 'existing@example.com', 'Existing user', 'USER', 'ACTIVE', true, now(), now())");
            jdbc.update("insert into " + schema + ".meals(id, user_id, meal_type, meal_date, created_at, updated_at) "
                    + "values (1, 1, 'LUNCH', '2026-10-05', now(), now())");
            jdbc.update("insert into " + schema + ".meal_items(id, meal_id, input_name, normalized_name, quantity_grams, calories, "
                    + "protein_grams, carbohydrate_grams, fat_grams, created_at, updated_at) "
                    + "values (1, 1, 'Chicken breast', 'chicken', 150.25, 248.50, 46.50, 0, 5.40, now(), now())");
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from information_schema.columns "
                    + "where table_schema = ? and table_name = 'meal_items' and column_name = 'normalized_name'",
                    Integer.class, schema)).isZero();
            var row = jdbc.queryForMap("select id, meal_id, input_name, quantity_grams, calories, "
                    + "protein_grams, carbohydrate_grams, fat_grams from " + schema + ".meal_items where id = 1");
            assertThat(row.get("id")).isEqualTo(1L);
            assertThat(row.get("meal_id")).isEqualTo(1L);
            assertThat(row.get("input_name")).isEqualTo("Chicken breast");
            assertThat((BigDecimal) row.get("quantity_grams")).isEqualByComparingTo("150.25");
            assertThat((BigDecimal) row.get("calories")).isEqualByComparingTo("248.50");
            assertThat((BigDecimal) row.get("protein_grams")).isEqualByComparingTo("46.50");
            assertThat((BigDecimal) row.get("carbohydrate_grams")).isEqualByComparingTo("0");
            assertThat((BigDecimal) row.get("fat_grams")).isEqualByComparingTo("5.40");
        } finally {
            upgraded.clean();
        }
    }

    @Test void decimalMigrationPreservesExistingIntegerNutritionAndConstraints() {
        String schema = "ai_migration_" + UUID.randomUUID().toString().replace("-", "");
        var previous = org.flywaydb.core.Flyway.configure().dataSource(dataSource)
                .schemas(schema).defaultSchema(schema).target("8").load();
        var upgraded = org.flywaydb.core.Flyway.configure().dataSource(dataSource)
                .schemas(schema).defaultSchema(schema).target("9").cleanDisabled(false).load();
        try {
            previous.migrate();
            jdbc.update("insert into " + schema + ".users(id, email, full_name, role, status, email_verified, created_at, updated_at) "
                    + "values (1, 'existing@example.com', 'Existing user', 'USER', 'ACTIVE', true, now(), now())");
            jdbc.update("insert into " + schema + ".meals(id, user_id, meal_type, meal_date, created_at, updated_at) "
                    + "values (1, 1, 'LUNCH', '2026-09-30', now(), now())");
            jdbc.update("insert into " + schema + ".meal_items(id, meal_id, input_name, quantity_grams, calories, "
                    + "protein_grams, carbohydrate_grams, fat_grams, created_at, updated_at) "
                    + "values (1, 1, 'Existing item', 100.25, 2147483647, 10, 20, 0, now(), now())");
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(jdbc.queryForObject("select calories from " + schema + ".meal_items where id = 1", BigDecimal.class))
                    .isEqualByComparingTo("2147483647");
            assertThat(jdbc.queryForObject("select quantity_grams from " + schema + ".meal_items where id = 1", BigDecimal.class))
                    .isEqualByComparingTo("100.25");
            jdbc.update("update " + schema + ".meal_items set protein_grams = 55.8, fat_grams = 6.5 where id = 1");
            assertThat(jdbc.queryForObject("select protein_grams from " + schema + ".meal_items where id = 1", BigDecimal.class))
                    .isEqualByComparingTo("55.8");
            assertThatThrownBy(() -> jdbc.update("update " + schema + ".meal_items set fat_grams = -1 where id = 1"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            upgraded.clean();
        }
    }
}
