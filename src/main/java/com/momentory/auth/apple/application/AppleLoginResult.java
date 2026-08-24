package com.momentory.auth.apple.application;

import java.time.Duration;

public record AppleLoginResult(
        String accessToken,
        String refreshToken,
        Duration accessTokenExpiresIn,
        Long userId,
        boolean onboardingRequired
) {
}
