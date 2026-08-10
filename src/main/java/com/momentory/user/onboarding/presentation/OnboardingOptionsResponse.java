package com.momentory.user.onboarding.presentation;

import com.momentory.user.onboarding.application.NicknamePolicyResult;
import com.momentory.user.onboarding.application.OnboardingOptionResult;
import com.momentory.user.onboarding.application.OnboardingOptionsResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record OnboardingOptionsResponse(
        NicknamePolicyResponse nickname,
        List<OptionResponse> genders,
        List<OptionResponse> interestAreas,
        List<OptionResponse> restMethods,
        @Schema(description = "회고 시간 형식", example = "HH:mm") String reflectionTimeFormat,
        @Schema(description = "기본 시간대", example = "Asia/Seoul") String defaultTimeZone
) {

    static OnboardingOptionsResponse from(OnboardingOptionsResult result) {
        return new OnboardingOptionsResponse(
                NicknamePolicyResponse.from(result.nickname()),
                result.genders().stream().map(OptionResponse::from).toList(),
                result.interestAreas().stream().map(OptionResponse::from).toList(),
                result.restMethods().stream().map(OptionResponse::from).toList(),
                result.reflectionTimeFormat(),
                result.defaultTimeZone()
        );
    }

    public record NicknamePolicyResponse(
            @Schema(description = "닉네임 최대 길이", example = "10") int maxLength,
            @Schema(description = "닉네임 중복 허용 여부", example = "true") boolean duplicateAllowed
    ) {
        static NicknamePolicyResponse from(NicknamePolicyResult result) {
            return new NicknamePolicyResponse(result.maxLength(), result.duplicateAllowed());
        }
    }

    public record OptionResponse(
            String code,
            String label
    ) {
        static OptionResponse from(OnboardingOptionResult result) {
            return new OptionResponse(result.code(), result.label());
        }
    }
}
