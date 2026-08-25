package com.momentory.auth.google.application;

import java.time.Duration;

public record GoogleLoginResult(
        String accessToken,
        String refreshToken,
        Duration accessTokenExpiresIn,
        Long userId,
        boolean onboardingRequired
) {
}
