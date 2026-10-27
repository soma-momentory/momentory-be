package com.momentory.user.me.application;

import com.momentory.user.domain.User;
import com.momentory.user.domain.UserProfile;
import com.momentory.user.application.AuthenticatedUserNotFoundException;
import com.momentory.user.infrastructure.UserProfileRepository;
import com.momentory.user.infrastructure.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;

@Service
public class UserMeService {

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;

    public UserMeService(UserRepository userRepository, UserProfileRepository userProfileRepository) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
    }

    @Transactional(readOnly = true)
    public UserMeResult getUserMe(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(AuthenticatedUserNotFoundException::new);
        // 온보딩 전에는 프로필 행이 없다 — 없음을 오류로 보지 않는다
        UserMeResult.Profile profile = userProfileRepository.findById(userId)
                .map(UserMeService::toProfile)
                .orElse(null);
        return new UserMeResult(
                user.getId(),
                user.getRole(),
                user.requiresOnboarding(),
                user.getOnboardingVersion(),
                User.LATEST_ONBOARDING_VERSION,
                profile
        );
    }

    /** 편지 알림 시각 저장 — 프로필은 온보딩 때 생기므로, 없으면 온보딩을 먼저 마쳐야 한다. */
    @Transactional
    public LetterPreference updateLetterPreference(Long userId, LocalTime letterTime, boolean enabled) {
        UserProfile profile = userProfileRepository.findById(userId)
                .orElseThrow(OnboardingRequiredException::new);
        profile.changeLetterPreference(letterTime, enabled);
        return new LetterPreference(profile.getLetterTime(), profile.isLetterNotificationEnabled());
    }

    public record LetterPreference(LocalTime letterTime, boolean notificationEnabled) {
    }

    private static UserMeResult.Profile toProfile(UserProfile profile) {
        return new UserMeResult.Profile(
                profile.getNickname(),
                profile.getAge(),
                profile.getGender(),
                profile.getInterestAreas(),
                profile.getOtherInterestDetail(),
                profile.getRestMethods(),
                profile.getOtherRestMethodDetail(),
                profile.getReflectionTime(),
                profile.isCalendarIntegrationEnabled(),
                profile.isNotificationEnabled(),
                profile.getLetterTime(),
                profile.isLetterNotificationEnabled()
        );
    }
}
