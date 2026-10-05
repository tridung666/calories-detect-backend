package com.tridung.caloriesdetect.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tridung.caloriesdetect.exception.AppException;
import com.tridung.caloriesdetect.exception.ErrorCode;
import com.tridung.caloriesdetect.service.impl.RestMealAnalysisClient;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AiWireContractTest {
    @Test void consumesActualFastApiWireFixturesWithoutSendingMealIdentity() throws Exception {
        var json = new ObjectMapper();
        JsonNode contract;
        try (var stream = getClass().getResourceAsStream("/contracts/meal-analysis-contract.json")) {
            contract = json.readTree(stream);
        }
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            for (JsonNode scenario : contract.path("cases")) {
                var builder = RestClient.builder().baseUrl("http://ai:8000");
                var server = MockRestServiceServer.bindTo(builder).build();
                server.expect(requestTo("http://ai:8000/api/v1/meals/analyze"))
                        .andExpect(method(HttpMethod.POST))
                        .andExpect(content().json(contract.path("request").toString(), true))
                        .andExpect(headerDoesNotExist("Authorization"))
                        .andRespond(withStatus(HttpStatusCode.valueOf(scenario.path("status").asInt()))
                                .contentType(MediaType.APPLICATION_JSON).body(scenario.path("body").toString()));
                var client = new RestMealAnalysisClient(builder.build(), validation.getValidator());
                if (scenario.path("status").asInt() == 200) {
                    var result = client.analyze(987L, contract.path("request").path("imageUrl").asText());
                    assertThat(result.mealId()).isEqualTo(987L);
                    assertThat(json.readTree(json.writeValueAsString(result.items()))).isEqualTo(scenario.path("body").path("items"));
                } else {
                    assertThatThrownBy(() -> client.analyze(987L, contract.path("request").path("imageUrl").asText()))
                            .isInstanceOfSatisfying(AppException.class, exception ->
                                    assertThat(exception.getErrorCode()).isEqualTo(
                                            ErrorCode.valueOf(scenario.path("errorCode").asText())));
                }
                server.verify();
            }
        }
    }
}
