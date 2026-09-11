package com.tridung.caloriesdetect.exception;

import com.tridung.caloriesdetect.common.response.BaseResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
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

        return ResponseEntity.status(errorCode == ErrorCode.MEAL_NOT_FOUND || errorCode == ErrorCode.MEAL_ITEM_NOT_FOUND ? 404 : 400).body(BaseResponse.error(
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
