package com.momentory.auth.google.infrastructure;

public final class GoogleApiException extends RuntimeException {

    private final GoogleApiErrorCode errorCode;

    public GoogleApiException(GoogleApiErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public GoogleApiException(GoogleApiErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public GoogleApiErrorCode getErrorCode() {
        return errorCode;
    }
}
