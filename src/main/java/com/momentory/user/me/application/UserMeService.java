package com.momentory.user.me.application;

import com.momentory.user.domain.User;
import com.momentory.user.domain.UserProfile;
import com.momentory.user.application.AuthenticatedUserNotFoundException;
import com.momentory.user.infrastructure.UserProfileRepository;
import com.momentory.user.infrastructure.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        return new UserMeResult(user.getId(), user.getRole(), user.requiresOnboarding(), profile);
    }

    private static UserMeResult.Profile toProfile(UserProfile profile) {
        return new UserMeResult.Profile(
                profile.getNickname(),
                profile.getAge(),
                profile.getGender(),
                profile.getInterestAreas(),
                profile.getReflectionTime(),
                profile.isCalendarIntegrationEnabled()
        );
    }
}
