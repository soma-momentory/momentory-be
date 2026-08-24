package com.momentory.auth.apple.infrastructure;

public final class AppleApiException extends RuntimeException {

    private final AppleApiErrorCode errorCode;

    public AppleApiException(AppleApiErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AppleApiException(AppleApiErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public AppleApiErrorCode getErrorCode() {
        return errorCode;
    }
}
