package com.tridung.caloriesdetect.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {

    USER_EXISTED(10001, "User already exists"),
    USER_NOT_FOUND(10002, "User not found"),
    UNAUTHORIZED(10003, "Unauthorized");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}