package com.momentory.auth.google.presentation;

import com.momentory.auth.google.application.GoogleLoginResult;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 카카오·애플 응답과 <b>글자 그대로 같다.</b> FE 가 세 공급자의 교환 뒤를
 * {@code adoptSession} 하나로 나눠 쓰는 근거이므로, 이 모양은 바꾸지 않는다.
 */
public record GoogleLoginResponse(
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

    public static GoogleLoginResponse from(GoogleLoginResult result) {
        return new GoogleLoginResponse(
                result.accessToken(),
                result.refreshToken(),
                "Bearer",
                result.accessTokenExpiresIn().toSeconds(),
                result.userId(),
                result.onboardingRequired()
        );
    }
}
