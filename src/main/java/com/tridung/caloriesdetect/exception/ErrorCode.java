package com.tridung.caloriesdetect.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {

    // User
    USER_EXISTED(10001, "Email already exists"),
    USER_NOT_FOUND(10002, "User not found"),

    // Authentication
    UNAUTHORIZED(10003, "Unauthorized"),
    INVALID_CREDENTIALS(10004, "Invalid email or password"),

    // Access Token
    INVALID_TOKEN(10005, "Invalid token"),
    EXPIRED_TOKEN(10006, "Token has expired"),
    TOKEN_SIGNATURE_INVALID(10007, "Token signature is invalid"),

    // Refresh Token
    REFRESH_TOKEN_NOT_FOUND(10008, "Refresh token not found"),
    REFRESH_TOKEN_EXPIRED(10009, "Refresh token has expired"),
    REFRESH_TOKEN_REVOKED(10010, "Refresh token has been revoked"),
    INVALID_REFRESH_TOKEN(10011, "Invalid refresh token");


    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}