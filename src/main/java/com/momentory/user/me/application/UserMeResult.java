package com.momentory.user.me.application;

import com.momentory.user.domain.Gender;
import com.momentory.user.domain.InterestArea;
import com.momentory.user.domain.UserRole;

import java.time.LocalTime;
import java.util.Set;

public record UserMeResult(
        Long userId,
        UserRole role,
        boolean onboardingRequired,
        /** 온보딩 전에는 프로필이 없다 — 그때만 {@code null} 이다 */
        Profile profile
) {

    /**
     * 온보딩에서 받은 값 그대로. 클라이언트는 앱을 다시 열 때 이걸로 화면을 채운다 —
     * 저장은 서버가 하는데 읽을 길이 없으면 기기가 자기 값을 기억해야 하고,
     * 그러면 기기마다 다른 이름으로 불리게 된다.
     */
    public record Profile(
            String nickname,
            Integer age,
            Gender gender,
            Set<InterestArea> interestAreas,
            LocalTime reflectionTime,
            boolean calendarIntegrationEnabled
    ) {
    }
}
