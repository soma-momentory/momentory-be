package com.momentory.user.onboarding.application;

import com.momentory.user.domain.Gender;
import com.momentory.user.domain.InterestArea;
import com.momentory.user.domain.RestMethod;
import com.momentory.user.domain.UserProfile;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class OnboardingOptionsService {

    public OnboardingOptionsResult getOptions() {
        return new OnboardingOptionsResult(
                new NicknamePolicyResult(UserProfile.NICKNAME_MAX_LENGTH, true),
                toOptions(Gender.values()),
                toOptions(InterestArea.values()),
                toOptions(RestMethod.values()),
                "HH:mm",
                UserProfile.DEFAULT_TIME_ZONE
        );
    }

    private List<OnboardingOptionResult> toOptions(Gender[] genders) {
        return Arrays.stream(genders)
                .map(gender -> new OnboardingOptionResult(gender.name(), gender.getLabel()))
                .toList();
    }

    private List<OnboardingOptionResult> toOptions(InterestArea[] interestAreas) {
        return Arrays.stream(interestAreas)
                .map(interestArea -> new OnboardingOptionResult(interestArea.name(), interestArea.getLabel()))
                .toList();
    }

    private List<OnboardingOptionResult> toOptions(RestMethod[] restMethods) {
        return Arrays.stream(restMethods)
                .map(restMethod -> new OnboardingOptionResult(restMethod.name(), restMethod.getLabel()))
                .toList();
    }
}
