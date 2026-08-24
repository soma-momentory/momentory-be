package com.momentory.auth.apple.presentation;

import com.momentory.auth.apple.application.AppleLoginResult;
import io.swagger.v3.oas.annotations.media.Schema;

public record AppleLoginResponse(
        @Schema(description = "모멘토리 Access Token")
        String accessToken,
        @Schema(description = "모멘토리 Refresh Token")
        String refreshToken,
        @Schema(description = "Access Token 인증 방식", example = "Bearer")
        String tokenType,
        @Schema(description = "Access Token 만료까지 남은 시간(초)", example = "1800")
        long accessTokenExpiresIn,
        @Schema(description = "모멘토리 사용자 ID", example = "1")
        Long userId,
        @Schema(description = "온보딩 필요 여부", example = "true")
        boolean onboardingRequired
) {

    public static AppleLoginResponse from(AppleLoginResult result) {
        return new AppleLoginResponse(
                result.accessToken(),
                result.refreshToken(),
                "Bearer",
                result.accessTokenExpiresIn().toSeconds(),
                result.userId(),
                result.onboardingRequired()
        );
    }
}
