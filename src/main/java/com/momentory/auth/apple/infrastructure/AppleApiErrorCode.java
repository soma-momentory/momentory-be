package com.momentory.auth.apple.infrastructure;

public enum AppleApiErrorCode {
    INVALID_IDENTITY_TOKEN,
    CLIENT_ID_MISMATCH,
    NONCE_MISMATCH,
    EMAIL_UNAVAILABLE,
    APPLE_API_SERVER_ERROR,
    APPLE_API_NETWORK_ERROR,
    UNEXPECTED_APPLE_RESPONSE
}
