package com.momentory.user.onboarding.application;

import java.util.List;

public record OnboardingOptionsResult(
        NicknamePolicyResult nickname,
        List<OnboardingOptionResult> genders,
        List<OnboardingOptionResult> interestAreas,
        List<OnboardingOptionResult> restMethods,
        String reflectionTimeFormat,
        String defaultTimeZone
) {
}
