package com.momentory.user.onboarding.application;

import com.momentory.user.domain.Gender;
import com.momentory.user.domain.InterestArea;
import com.momentory.user.domain.OnboardingResponse;
import com.momentory.user.domain.RestMethod;
import com.momentory.user.domain.User;
import com.momentory.user.domain.UserProfile;
import com.momentory.user.application.AuthenticatedUserNotFoundException;
import com.momentory.user.infrastructure.OnboardingResponseRepository;
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
    private final OnboardingResponseRepository onboardingResponseRepository;

    public CompleteOnboardingService(
            UserRepository userRepository,
            UserProfileRepository userProfileRepository,
            OnboardingResponseRepository onboardingResponseRepository
    ) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.onboardingResponseRepository = onboardingResponseRepository;
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

        return CompleteOnboardingResult.from(user);
    }

    /**
     * 새 온보딩(v2) 완료. 받은 항목만 고치고 관심사 · 쉬는 방법은 건드리지 않는다.
     * 처음 가입한 사용자는 관심사 · 쉬는 방법이 빈 프로필을 만든다. 같은 요청이 다시 와도 결과가 같다.
     */
    @Transactional
    public CompleteOnboardingResult completeLatest(
            Long userId,
            String nickname,
            Integer age,
            Gender gender,
            LocalTime reflectionTime,
            boolean calendarIntegrationEnabled,
            boolean notificationEnabled,
            OnboardingResponse.MoodChange moodChange,
            OnboardingResponse.KnowWhy knowWhy,
            OnboardingResponse.DayEnd dayEnd,
            OnboardingResponse.DiaryHistory diaryHistory,
            Set<OnboardingResponse.Goal> goals
    ) {
        User user = userRepository.findById(userId)
                .orElseThrow(AuthenticatedUserNotFoundException::new);

        UserProfile userProfile = userProfileRepository.findById(userId)
                .map(profile -> {
                    profile.updateBasicInfo(nickname, age, gender, reflectionTime, calendarIntegrationEnabled, notificationEnabled);
                    return profile;
                })
                .orElseGet(() -> UserProfile.create(
                        user,
                        nickname,
                        age,
                        gender,
                        Set.of(),
                        null,
                        null,
                        null,
                        reflectionTime,
                        calendarIntegrationEnabled,
                        notificationEnabled
                ));
        userProfileRepository.save(userProfile);

        int version = User.LATEST_ONBOARDING_VERSION;
        OnboardingResponse response = onboardingResponseRepository.findById(new OnboardingResponse.Id(userId, version))
                .orElseGet(() -> new OnboardingResponse(userId, version));
        response.answer(moodChange, knowWhy, dayEnd, diaryHistory, goals);
        onboardingResponseRepository.save(response);

        user.completeLatestOnboarding();
        return CompleteOnboardingResult.from(user);
    }
}
