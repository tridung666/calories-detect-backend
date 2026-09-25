package com.tridung.caloriesdetect.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tridung.caloriesdetect.common.enums.UserRole;
import com.tridung.caloriesdetect.common.enums.UserStatus;
import com.tridung.caloriesdetect.entity.User;
import com.tridung.caloriesdetect.entity.AuthProvider;
import com.tridung.caloriesdetect.repository.AuthProviderRepository;
import com.tridung.caloriesdetect.common.enums.AuthProviderType;

import com.tridung.caloriesdetect.repository.MealRepository;
import com.tridung.caloriesdetect.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MealItemIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AuthProviderRepository providers;
    @Autowired MealRepository meals;
    @Autowired PasswordEncoder encoder;
    private final ObjectMapper json = new ObjectMapper();
    private static final String BODY = "{\"mealType\":\"SNACK\",\"mealDate\":\"1958-06-07\"}";

    private User createUser() {
        User user = users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@example.com")
                 .fullName("Meal integration test")
                .role(UserRole.USER).status(UserStatus.ACTIVE).emailVerified(true).build());
        providers.saveAndFlush(AuthProvider.builder().user(user).provider(AuthProviderType.LOCAL)
                .passwordHash(encoder.encode("TestPassword123!")).build());
        return user;
    }
    private String login(User user, String previousToken) throws Exception {
        var request = post("/api/auth/login").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json")
                .content("{\"email\":\""+user.getEmail()+"\",\"password\":\"TestPassword123!\"}");
        if (previousToken != null) request.header("Authorization", "Bearer "+previousToken);
        String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data").path("accessToken").asText();
    }
    @Autowired com.tridung.caloriesdetect.repository.MealItemRepository items;
    @Autowired jakarta.persistence.EntityManager entityManager;
    private static final String ITEM = """
            {"inputName":"Rice", "normalizedName":"rice", "quantityGrams":150.25,
             "calories":195, "proteinGrams":4, "carbohydrateGrams":42, "fatGrams":0}
            """;

    private long createMeal(String token) throws Exception {
        String body = mvc.perform(post("/api/meal/create").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data").path("id").asLong();
    }

    private long addItem(String token, long mealId) throws Exception {
        String body = mvc.perform(post("/api/meal/{mealId}/items", mealId).header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(ITEM))
                .andExpect(status().isOk()).andExpect(jsonPath("data.mealId").value(mealId))
                .andExpect(jsonPath("data.quantityGrams").value(150.25))
                .andExpect(jsonPath("data.calories").value(195))
                .andExpect(jsonPath("data.proteinGrams").value(4))
                .andExpect(jsonPath("data.carbohydrateGrams").value(42))
                .andExpect(jsonPath("data.fatGrams").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data").path("id").asLong();
    }

    @Test void ownerCanCreateListReadUpdateAndDelete() throws Exception {
        String token = login(createUser(), null);
        long meal = createMeal(token);
        String base = "/api/meal/" + meal + "/items";
        mvc.perform(get(base).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("data").isEmpty());
        long item = addItem(token, meal);
        mvc.perform(get(base).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("data.length()").value(1));
        mvc.perform(get(base + "/" + item).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("data.inputName").value("Rice"));
        mvc.perform(put(base + "/" + item).header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(ITEM.replace("195", "200").replace("\"rice\"", "null")))
                .andExpect(status().isOk()).andExpect(jsonPath("data.calories").value(200));
        entityManager.flush();
        entityManager.clear();
        assertEquals(200, items.findById(item).orElseThrow().getCalories());
        org.junit.jupiter.api.Assertions.assertNull(items.findById(item).orElseThrow().getNormalizedName());
        mvc.perform(delete(base + "/" + item).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get(base + "/" + item).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("code").value(14002));
    }

    @Test void otherUserCannotAccessAnyItemEndpoint() throws Exception {
        String owner = login(createUser(), null);
        long meal = createMeal(owner);
        long item = addItem(owner, meal);
        String other = login(createUser(), null);
        String base = "/api/meal/" + meal + "/items";
        for (var request : java.util.List.of(get(base), post(base).contentType("application/json").content(ITEM),
                get(base + "/" + item), put(base + "/" + item).contentType("application/json").content(ITEM),
                delete(base + "/" + item))) {
            mvc.perform(request.header("Authorization", "Bearer " + other)).andExpect(status().isNotFound());
        }
        assertEquals(1, items.findAllByMeal_IdOrderByIdAsc(meal).size());
    }

    @Test void itemMustBelongToMealInPath() throws Exception {
        String token = login(createUser(), null);
        long meal = createMeal(token);
        long item = addItem(token, meal);
        String wrongPath = "/api/meal/" + createMeal(token) + "/items/" + item;
        for (var request : java.util.List.of(get(wrongPath), delete(wrongPath),
                put(wrongPath).contentType("application/json").content(ITEM))) {
            mvc.perform(request.header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("code").value(14002));
        }
    }

    @Test void invalidInputIsRejectedForCreateAndUpdate() throws Exception {
        String token = login(createUser(), null);
        long meal = createMeal(token);
        long item = addItem(token, meal);
        String base = "/api/meal/" + meal + "/items";
        var invalid = new java.util.ArrayList<String>();
        invalid.add("{}");
        invalid.add(ITEM.replace("Rice", " "));
        invalid.add(ITEM.replace("Rice", "x".repeat(256)));
        invalid.add(ITEM.replace("rice", "x".repeat(256)));
        for (String field : java.util.List.of("quantityGrams", "calories", "proteinGrams", "carbohydrateGrams", "fatGrams")) {
            for (String value : java.util.List.of("null", "-1", field.equals("quantityGrams") ? "100000000" : "2147483648", "0.001")) {
                var body = json.readTree(ITEM);
                ((com.fasterxml.jackson.databind.node.ObjectNode) body).set(field, json.readTree(value));
                invalid.add(body.toString());
            }
        }
        invalid.add(ITEM.replace("150.25", "0"));
        for (String body : invalid) {
            mvc.perform(post(base).header("Authorization", "Bearer " + token).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
            mvc.perform(put(base + "/" + item).header("Authorization", "Bearer " + token).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test void missingMealReturnsNotFound() throws Exception {
        String token = login(createUser(), null);
        mvc.perform(post("/api/meal/0/items").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(ITEM))
                .andExpect(status().isNotFound()).andExpect(jsonPath("code").value(14000));
    }

    @Test void deletingMealCascadesToItems() throws Exception {
        String token = login(createUser(), null);
        long meal = createMeal(token);
        long item = addItem(token, meal);
        mvc.perform(delete("/api/meal/" + meal).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        org.junit.jupiter.api.Assertions.assertTrue(items.findById(item).isEmpty());
    }

    @Test void allEndpointsRequireAuthentication() throws Exception {
        String base = "/api/meal/1/items";
        for (var request : java.util.List.of(get(base), post(base).contentType("application/json").content(ITEM),
                get(base + "/1"), put(base + "/1").contentType("application/json").content(ITEM), delete(base + "/1"))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
    }
}
