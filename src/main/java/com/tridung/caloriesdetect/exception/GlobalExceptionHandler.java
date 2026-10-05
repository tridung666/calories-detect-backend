package com.tridung.caloriesdetect.exception;

import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import com.tridung.caloriesdetect.common.response.BaseResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<BaseResponse<Void>> handleMaxUploadSizeExceededException() {
        return handleAppException(new AppException(ErrorCode.IMAGE_TOO_LARGE));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<BaseResponse<Void>> handleMissingImagePart() {
        return handleAppException(new AppException(ErrorCode.INVALID_IMAGE));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<BaseResponse<Void>> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException exception
    ) {
        return ResponseEntity.badRequest().body(BaseResponse.error(
                400, "Invalid JSON request: check field types, enum values and date format"
        ));
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<BaseResponse<Void>>  handleAppException(AppException exception) {
        ErrorCode errorCode = exception.getErrorCode();

        int status = switch (errorCode) {
            case MEAL_NOT_FOUND, MEAL_ITEM_NOT_FOUND -> 404;
            case AI_SERVICE_UNAVAILABLE -> 503;
            case AI_SERVICE_TIMEOUT -> 504;
            case INVALID_AI_RESPONSE -> 502;
            case AI_EMPTY_RESULT, AI_IMAGE_UNREADABLE -> 422;
            case EMAIL_DELIVERY_FAILED, IMAGE_UPLOAD_FAILED, IMAGE_DELETE_FAILED -> 503;
            case IMAGE_TOO_LARGE -> 413;
            case OTP_RATE_LIMITED -> 429;
            case REFRESH_TOKEN_NOT_FOUND, REFRESH_TOKEN_EXPIRED, REFRESH_TOKEN_REVOKED, INVALID_REFRESH_TOKEN -> 401;
            default -> 400;
        };
        return ResponseEntity.status(status).body(BaseResponse.error(
                errorCode.getCode(),
                errorCode.getMessage()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<BaseResponse<Void>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception
    ) {
        FieldError fieldError = exception.getBindingResult().getFieldError();
        String message = fieldError == null
                ? "Invalid request"
                : fieldError.getDefaultMessage();

        return ResponseEntity.badRequest().body(BaseResponse.error(
                400,
                message
        ));
    }
}
