package com.momentory.user.onboarding.presentation;

import com.momentory.user.onboarding.application.CompleteOnboardingResult;
import io.swagger.v3.oas.annotations.media.Schema;

public record CompleteOnboardingV2Response(
        @Schema(description = "사용자 ID", example = "1") Long userId,
        @Schema(description = "온보딩 필요 여부", example = "false") boolean onboardingRequired,
        @Schema(description = "사용자가 마친 온보딩 판", example = "2") int onboardingVersion
) {

    static CompleteOnboardingV2Response from(CompleteOnboardingResult result) {
        return new CompleteOnboardingV2Response(result.userId(), result.onboardingRequired(), result.onboardingVersion());
    }
}
