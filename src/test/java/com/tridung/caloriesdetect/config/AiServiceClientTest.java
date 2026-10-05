package com.tridung.caloriesdetect.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tridung.caloriesdetect.exception.AppException;
import com.tridung.caloriesdetect.exception.ErrorCode;
import com.tridung.caloriesdetect.service.impl.RestMealAnalysisClient;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;

class AiServiceClientTest {
    private static final String IMAGE = "https://res.cloudinary.com/test/image/upload/meal.jpg";
    private static final String VALID = """
            {"items":[{"name":"Grilled chicken breast","estimatedGrams":150,
            "calories":248,"protein":46.5,"carbohydrate":0,"fat":5.4,"confidence":0.91}]}
            """;
    private HttpServer server;
    private ExecutorService executor;
    private ValidatorFactory validation;
    private RestMealAnalysisClient client;
    private volatile String response = VALID;
    private volatile int status = 200;
    private volatile String contentType = "application/json";
    private volatile long headerDelayMillis;
    private volatile long bodyDelayMillis;
    private volatile String requestBody;
    private volatile String requestMethod;
    private volatile String requestAuthorization;

    @BeforeEach void setup() throws Exception {
        validation = Validation.buildDefaultValidatorFactory();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/api/v1/meals/analyze", exchange -> {
            try (exchange) {
                requestMethod = exchange.getRequestMethod();
                requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requestAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                try { Thread.sleep(headerDelayMillis); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); return; }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(status, bytes.length);
                if (bodyDelayMillis > 0) {
                    exchange.getResponseBody().write(bytes, 0, 1);
                    exchange.getResponseBody().flush();
                    try { Thread.sleep(bodyDelayMillis); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); return; }
                    exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
                } else {
                    exchange.getResponseBody().write(bytes);
                }
            }
        });
        server.start();
        configureClient(Duration.ofSeconds(2));
    }

    private void configureClient(Duration timeout) {
        var properties = new AiServiceProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofMillis(200), timeout);
        var config = new AiServiceConfig();
        client = new RestMealAnalysisClient(config.aiRestClient(properties), validation.getValidator());
    }

    @AfterEach void cleanup() {
        server.stop(0);
        executor.shutdownNow();
        validation.close();
    }

    @Test void sendsOnlyStoredUrlAndAttachesPathMealIdToPrediction() throws Exception {
        var result = client.analyze(123L, IMAGE);
        assertThat(result.mealId()).isEqualTo(123L);
        assertThat(result.items().getFirst().protein()).isEqualByComparingTo("46.5");
        assertThat(result.items().getFirst().confidence()).isEqualByComparingTo("0.91");
        assertThat(requestMethod).isEqualTo("POST");
        var body = new ObjectMapper().readTree(requestBody);
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.has("mealId")).isFalse();
        assertThat(body.get("imageUrl").asText()).isEqualTo(IMAGE);
        assertThat(requestAuthorization).isNull();
    }

    static Stream<String> invalidResponses() {
        return Stream.of("not json", "null", "{}", "{\"mealId\":123}",
                "{\"mealId\":123,\"items\":null}", "{\"mealId\":123,\"items\":[null]}",
                VALID.replace("46.5", "-1"),
                VALID.replace("150", "0"), VALID.replace("0.91", "1.1"),
                VALID.replace("150", "100000000"), VALID.replace("150", "150.001"),
                VALID.replace("248", "2147483648"), VALID.replace("46.5", "46.555"),
                VALID.replace("Grilled chicken breast", "x".repeat(256)),
                VALID.replace("Grilled chicken breast", " "), VALID.replace("\"protein\":46.5,", ""));
    }

    @ParameterizedTest @MethodSource("invalidResponses")
    void rejectsMalformedIncompleteOrInvalidPredictions(String body) {
        response = body;
        assertError(ErrorCode.INVALID_AI_RESPONSE);
    }

    @Test void rejectsNonJsonContent() {
        contentType = "text/plain";
        assertError(ErrorCode.INVALID_AI_RESPONSE);
    }

    @Test void rejectsEmptyDetection() {
        response = "{\"mealId\":123,\"items\":[]}";
        assertError(ErrorCode.AI_EMPTY_RESULT);
    }

    @Test void rejectsEmptyHttpBody() {
        response = "";
        assertError(ErrorCode.INVALID_AI_RESPONSE);
    }

    @ParameterizedTest @ValueSource(ints = {302, 400, 401, 403, 429, 500, 503})
    void mapsDownstreamErrorsWithoutExposingTheirBody(int statusCode) {
        status = statusCode;
        response = "Internal provider details";
        assertError(ErrorCode.AI_SERVICE_UNAVAILABLE);
    }

    @Test void mapsProviderTimeout() {
        status = 504;
        response = "{\"code\":\"analysis_failed\",\"detail\":\"private provider details\"}";
        assertError(ErrorCode.AI_SERVICE_TIMEOUT);
    }

    @Test void mapsInvalidProviderOutput() {
        status = 502;
        assertError(ErrorCode.INVALID_AI_RESPONSE);
    }

    @Test void mapsNoFoodDetectionFromAi() {
        status = 422;
        response = "{\"code\":\"no_food_detected\",\"detail\":\"No food detected in the image.\"}";
        assertError(ErrorCode.AI_EMPTY_RESULT);
    }

    @Test void mapsUnreadableImageFromAi() {
        status = 422;
        response = "{\"code\":\"invalid_image\",\"detail\":\"private image URL\"}";
        assertError(ErrorCode.AI_IMAGE_UNREADABLE);
    }

    @ParameterizedTest @ValueSource(strings = {"not json", "{}", "null", "[]", "{\"detail\":[]}"})
    void rejectsUnknownValidationError(String body) {
        status = 422;
        response = body;
        assertError(ErrorCode.INVALID_AI_RESPONSE);
    }

    @Test void rejectsOversizedErrorBody() {
        status = 422;
        response = "x".repeat(5000);
        assertError(ErrorCode.INVALID_AI_RESPONSE);
    }

    @Test void mapsConnectionFailure() {
        server.stop(0);
        assertError(ErrorCode.AI_SERVICE_UNAVAILABLE);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void boundsWaitingForHeadersAndResponseBody(boolean delayHeaders) {
        configureClient(Duration.ofMillis(100));
        if (delayHeaders) headerDelayMillis = 500;
        else bodyDelayMillis = 500;
        assertError(ErrorCode.AI_SERVICE_TIMEOUT);
    }

    @Test void mapsConnectionTimeout() {
        var builder = RestClient.builder();
        var mockServer = MockRestServiceServer.bindTo(builder).build();
        mockServer.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("/api/v1/meals/analyze"))
                .andRespond(withException(new HttpConnectTimeoutException("connect timed out")));
        client = new RestMealAnalysisClient(builder.build(), validation.getValidator());
        assertError(ErrorCode.AI_SERVICE_TIMEOUT);
        mockServer.verify();
    }

    @Test void validatesConfiguration() {
        assertThat(validation.getValidator().validate(new AiServiceProperties(URI.create("file:///tmp/ai"),
                Duration.ZERO, Duration.ofSeconds(-1)))).hasSize(2);
    }

    private void assertError(ErrorCode code) {
        assertThatThrownBy(() -> client.analyze(123L, IMAGE)).isInstanceOfSatisfying(AppException.class,
                exception -> assertThat(exception.getErrorCode())
                        .withFailMessage("Expected %s; received %s", code, org.assertj.core.util.Throwables.getStackTrace(exception))
                        .isEqualTo(code));
    }
}
