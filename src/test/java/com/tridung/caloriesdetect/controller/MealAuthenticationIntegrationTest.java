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
class MealAuthenticationIntegrationTest {
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
    private void assertOwner(String token, Long userId) throws Exception {
        String body = mvc.perform(post("/api/meal/create").header("Authorization", "Bearer "+token)
                .contentType("application/json").content(BODY)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long mealId = json.readTree(body).path("data").path("id").asLong();
        assertEquals(userId, meals.findById(mealId).orElseThrow().getUserId().getId());
    }
    @Test void loginSwitchUsesNewTokenAndOldTokenStillIdentifiesOldUser() throws Exception {
        User first = createUser();
        User second = createUser();
        String firstToken = login(first, null);
        String secondToken = login(second, firstToken);
        assertOwner(secondToken, second.getId());
        assertOwner(firstToken, first.getId());
    }
    @Test void missingAndMalformedBearerAreRejected() throws Exception {
        mvc.perform(post("/api/meal/create").contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
        String token = login(createUser(), null);
        mvc.perform(post("/api/meal/create").header("Authorization", "Bearer Bearer "+token)
                .contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
    }
    @Test void invalidEnumReturnsBadRequestWithValidToken() throws Exception {
        String token = login(createUser(), null);
        mvc.perform(post("/api/meal/create").header("Authorization", "Bearer "+token)
                .contentType("application/json").content(BODY.replace("SNACK", "LUNC")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value(400));
    }

    private long createMeal(String token, String body) throws Exception {
        String response = mvc.perform(post("/api/meal/create").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data").path("id").asLong();
    }

    @Test void ownerCanReadUpdateAndDeleteMeal() throws Exception {
        String token = login(createUser(), null);
        long id = createMeal(token, BODY);
        mvc.perform(get("/api/meal/{id}", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("data.mealType").value("SNACK"));
        mvc.perform(put("/api/meal/{id}", id).header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(BODY.replace("SNACK", "DINNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("data.mealType").value("DINNER"));
        mvc.perform(delete("/api/meal/{id}", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/meal/{id}", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("code").value(14000));
    }

    @Test void anotherUserCannotReadUpdateOrDeleteMeal() throws Exception {
        String owner = login(createUser(), null);
        long id = createMeal(owner, BODY);
        String other = login(createUser(), null);
        mvc.perform(get("/api/meal/{id}", id).header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/meal/{id}", id).header("Authorization", "Bearer " + other)
                        .contentType("application/json").content(BODY.replace("SNACK", "DINNER")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/meal/{id}", id).header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/meal/{id}", id).header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andExpect(jsonPath("data.mealType").value("SNACK"));
    }

    @Test void listFiltersPaginatesAndExcludesOtherUsers() throws Exception {
        String token = login(createUser(), null);
        createMeal(token, BODY);
        long newest = createMeal(token, BODY.replace("1958-06-07", "2026-09-09"));
        createMeal(token, BODY.replace("SNACK", "DINNER"));
        createMeal(login(createUser(), null), BODY);
        mvc.perform(get("/api/meal").header("Authorization", "Bearer " + token).param("pageSize", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("data.totalElements").value(3))
                .andExpect(jsonPath("data.data[0].id").value(newest))
                .andExpect(jsonPath("data.totalPages").value(3));
        mvc.perform(get("/api/meal").header("Authorization", "Bearer " + token)
                        .param("mealDate", "1958-06-07").param("mealType", "SNACK"))
                .andExpect(status().isOk()).andExpect(jsonPath("data.totalElements").value(1))
                .andExpect(jsonPath("data.data[0].mealType").value("SNACK"));
        mvc.perform(get("/api/meal").header("Authorization", "Bearer " + token).param("pageNo", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("data.data").isEmpty());
    }

    @Test void invalidPaginationFiltersAndUpdateAreRejected() throws Exception {
        String token = login(createUser(), null);
        for (String[] param : new String[][]{{"pageNo", "-1"}, {"pageSize", "0"}, {"pageSize", "101"},
                {"mealDate", "invalid"}, {"mealType", "invalid"}}) {
            mvc.perform(get("/api/meal").header("Authorization", "Bearer " + token).param(param[0], param[1]))
                    .andExpect(status().isBadRequest());
        }
        long id = createMeal(token, BODY);
        mvc.perform(put("/api/meal/{id}", id).header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test void allNewEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/meal")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/meal/1")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/meal/1").contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/meal/1")).andExpect(status().isUnauthorized());
    }
}
