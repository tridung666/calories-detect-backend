package com.tridung.caloriesdetect.service.impl;

import com.tridung.caloriesdetect.dto.response.meal.MealAnalysisResponse;
import com.tridung.caloriesdetect.dto.response.meal.MealAnalysisResponse.Item;
import com.tridung.caloriesdetect.exception.AppException;
import com.tridung.caloriesdetect.exception.ErrorCode;
import com.tridung.caloriesdetect.service.MealAnalysisClient;
import jakarta.validation.Validator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpTimeoutException;
import java.util.List;

@Service
public class RestMealAnalysisClient implements MealAnalysisClient {
    private final RestClient client;
    private final Validator validator;

    public RestMealAnalysisClient(@Qualifier("aiRestClient") RestClient client, Validator validator) {
        this.client = client;
        this.validator = validator;
    }

    @Override
    public MealAnalysisResponse analyze(Long mealId, String imageUrl) {
        AnalysisResponse prediction;
        try {
            prediction = client.post().uri("/api/v1/meals/analyze")
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(new AnalysisRequest(imageUrl)).retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                        int status = response.getStatusCode().value();
                        ErrorCode code = switch (status) {
                            case 504 -> ErrorCode.AI_SERVICE_TIMEOUT;
                            case 502 -> ErrorCode.INVALID_AI_RESPONSE;
                            case 422 -> unprocessableError(response.getBody());
                            default -> ErrorCode.AI_SERVICE_UNAVAILABLE;
                        };
                        throw new AppException(code);
                    })
                    .body(AnalysisResponse.class);
        } catch (ResourceAccessException exception) {
            ErrorCode code = hasTimeoutCause(exception) ? ErrorCode.AI_SERVICE_TIMEOUT : ErrorCode.AI_SERVICE_UNAVAILABLE;
            throw new AppException(code, exception);
        } catch (RestClientResponseException exception) {
            throw new AppException(ErrorCode.AI_SERVICE_UNAVAILABLE, exception);
        } catch (RestClientException exception) {
            // Timeouts while decoding the response body can be wrapped as conversion failures.
            throw new AppException(hasTimeoutCause(exception) ? ErrorCode.AI_SERVICE_TIMEOUT
                    : ErrorCode.INVALID_AI_RESPONSE, exception);
        }

        if (prediction == null || !validator.validate(prediction).isEmpty()) {
            throw new AppException(ErrorCode.INVALID_AI_RESPONSE);
        }
        if (prediction.items().isEmpty()) {
            throw new AppException(ErrorCode.AI_EMPTY_RESULT);
        }
        return new MealAnalysisResponse(mealId, prediction.items());
    }

    private boolean hasTimeoutCause(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
        }
        return false;
    }

    private ErrorCode unprocessableError(java.io.InputStream body) throws java.io.IOException {
        // Consume only a bounded machine-readable error; never expose provider details.
        byte[] bytes = body.readNBytes(4097);
        if (bytes.length > 4096) return ErrorCode.INVALID_AI_RESPONSE;
        try {
            var error = new com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes);
            return switch (error.path("code").asText()) {
                case "no_food_detected" -> ErrorCode.AI_EMPTY_RESULT;
                case "invalid_image" -> ErrorCode.AI_IMAGE_UNREADABLE;
                default -> ErrorCode.INVALID_AI_RESPONSE;
            };
        } catch (com.fasterxml.jackson.core.JsonProcessingException | NullPointerException exception) {
            return ErrorCode.INVALID_AI_RESPONSE;
        }
    }

    private record AnalysisRequest(String imageUrl) {}
    private record AnalysisResponse(@NotNull List<@NotNull @Valid Item> items) {}
}
