package com.momentory.user.onboarding.application;

import com.momentory.user.domain.Gender;
import com.momentory.user.domain.InterestArea;
import com.momentory.user.domain.RestMethod;
import com.momentory.user.domain.User;
import com.momentory.user.domain.UserProfile;
import com.momentory.user.application.AuthenticatedUserNotFoundException;
import com.momentory.user.infrastructure.UserProfileRepository;
import com.momentory.user.infrastructure.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.Set;

@Service
public class CompleteOnboardingService {

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;

    public CompleteOnboardingService(UserRepository userRepository, UserProfileRepository userProfileRepository) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
    }

    @Transactional
    public CompleteOnboardingResult complete(
            Long userId,
            String nickname,
            Integer age,
            Gender gender,
            Set<InterestArea> interestAreas,
            String otherInterestDetail,
            Set<RestMethod> restMethods,
            String otherRestMethodDetail,
            LocalTime reflectionTime,
            boolean calendarIntegrationEnabled,
            Boolean notificationEnabled
    ) {
        User user = userRepository.findById(userId)
                .orElseThrow(AuthenticatedUserNotFoundException::new);

        UserProfile userProfile = userProfileRepository.findById(userId)
                .map(profile -> {
                    profile.update(
                            nickname,
                            age,
                            gender,
                            interestAreas,
                            otherInterestDetail,
                            restMethods,
                            otherRestMethodDetail,
                            reflectionTime,
                            calendarIntegrationEnabled,
                            notificationEnabled
                    );
                    return profile;
                })
                .orElseGet(() -> UserProfile.create(
                        user,
                        nickname,
                        age,
                        gender,
                        interestAreas,
                        otherInterestDetail,
                        restMethods,
                        otherRestMethodDetail,
                        reflectionTime,
                        calendarIntegrationEnabled,
                        notificationEnabled
                ));

        user.completeOnboarding();
        userProfileRepository.save(userProfile);

        return new CompleteOnboardingResult(user.getId(), user.requiresOnboarding());
    }
}
