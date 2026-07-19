package com.tridung.caloriesdetect.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {

    // User
    USER_EXISTED(10001, "Email already exists"),
    USER_NOT_FOUND(10002, "Email not found"),
    ACCOUNT_INACTIVE(10003, "Account inactive"),

    // Authentication
    UNAUTHORIZED(11000, "Unauthorized"),
    INVALID_CREDENTIALS(11001, "Invalid email or password"),
    OLD_PASSWORD_NOT_MATCH(11002, "Old password not match"),
    PASSWORD_CONFIRM_NOT_MATCH(11003, "Password does not match"),

    // Access Token
    INVALID_TOKEN(12000, "Invalid token"),
    EXPIRED_TOKEN(12001, "Token has expired"),
    TOKEN_SIGNATURE_INVALID(12002, "Token signature is invalid"),

    // Refresh Token
    REFRESH_TOKEN_NOT_FOUND(13000, "Refresh token not found"),
    REFRESH_TOKEN_EXPIRED(13001, "Refresh token has expired"),
    REFRESH_TOKEN_REVOKED(13002, "Refresh token has been revoked"),
    INVALID_REFRESH_TOKEN(13003, "Invalid refresh token");


    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
