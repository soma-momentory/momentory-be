package com.momentory.user.onboarding.application;

import com.momentory.user.domain.User;

public record CompleteOnboardingResult(
        Long userId,
        boolean onboardingRequired,
        int onboardingVersion
) {

    static CompleteOnboardingResult from(User user) {
        return new CompleteOnboardingResult(user.getId(), user.requiresOnboarding(), user.getOnboardingVersion());
    }
}
