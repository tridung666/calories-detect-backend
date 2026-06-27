package com.tridung.caloriesdetect.exception;

import com.tridung.caloriesdetect.common.response.BaseResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(AppException.class)
    public ResponseEntity<BaseResponse<Void>>  handleAppException(AppException exception) {
        ErrorCode errorCode = exception.getErrorCode();

        return ResponseEntity.badRequest().body(BaseResponse.error(
                errorCode.getCode(),
                errorCode.getMessage()
        ));
    }
}
