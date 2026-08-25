package com.momentory.auth.google.application;

public record GoogleUserInfo(
        String providerUserId,
        String email
) {
}
