package com.momentory.user.me.presentation;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.momentory.user.domain.Gender;
import com.momentory.user.domain.InterestArea;
import com.momentory.user.domain.RestMethod;
import com.momentory.user.me.application.UserMeResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalTime;
import java.util.Set;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserMeResponse(
        @Schema(description = "사용자 ID", example = "1") Long userId,
        @Schema(description = "현재 사용자 역할", example = "USER") String role,
        @Schema(description = "온보딩 필요 여부. 한 번도 안 했을 때만 true 다", example = "true") boolean onboardingRequired,
        @Schema(description = "사용자가 마친 온보딩 판. 0 은 아직 안 함", example = "1") int onboardingVersion,
        @Schema(description = "지금 앱이 보여 주는 온보딩 판. onboardingVersion 이 이보다 낮으면 새 앱이 온보딩을 다시 보여 준다", example = "2")
        int latestOnboardingVersion,
        @Schema(description = "온보딩에서 받은 프로필. 온보딩 전에는 없다", nullable = true)
        Profile profile
) {

    /**
     * 온보딩 결과를 그대로 되돌려준다. 클라이언트가 앱을 다시 열었을 때 화면을
     * 채우는 값이며, {@code reflectionTime} 은 온보딩 요청과 같은 {@code HH:mm} 이다 —
     * 보낸 형식과 받는 형식이 다르면 왕복 한 번에 값이 어긋난다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Profile(
            @Schema(description = "닉네임", example = "모리") String nickname,
            @Schema(description = "나이", example = "25", nullable = true) Integer age,
            @Schema(description = "성별", example = "FEMALE") Gender gender,
            @Schema(description = "관심 분야", example = "[\"CAREER\", \"RELATIONSHIP\"]")
            Set<InterestArea> interestAreas,
            @Schema(description = "관심 분야 기타 직접 입력", example = "에세이 글쓰기", nullable = true)
            String otherInterestDetail,
            @Schema(description = "쉬는 방법", example = "[\"READING\", \"WALKING\"]")
            Set<RestMethod> restMethods,
            @Schema(description = "쉬는 방법 기타 직접 입력", example = "따뜻한 차 마시기", nullable = true)
            String otherRestMethodDetail,
            @Schema(description = "회고 시간", example = "21:30")
            @JsonFormat(pattern = "HH:mm") LocalTime reflectionTime,
            @Schema(description = "캘린더 연동 여부", example = "true") boolean calendarIntegrationEnabled,
            @Schema(description = "푸시 알림 수신 선호 여부", example = "true") boolean notificationEnabled,
            @Schema(description = "바바의 편지 알림 시각. 아직 고르지 않았으면 없다(앱은 08:30 으로 본다)", example = "08:30", nullable = true)
            @JsonFormat(pattern = "HH:mm") LocalTime letterTime,
            @Schema(description = "바바의 편지 알림 켜기 여부", example = "true") boolean letterNotificationEnabled
    ) {

        static Profile from(UserMeResult.Profile profile) {
            return new Profile(
                    profile.nickname(),
                    profile.age(),
                    profile.gender(),
                    profile.interestAreas(),
                    profile.otherInterestDetail(),
                    profile.restMethods(),
                    profile.otherRestMethodDetail(),
                    profile.reflectionTime(),
                    profile.calendarIntegrationEnabled(),
                    profile.notificationEnabled(),
                    profile.letterTime(),
                    profile.letterNotificationEnabled()
            );
        }
    }

    static UserMeResponse from(UserMeResult result) {
        return new UserMeResponse(
                result.userId(),
                result.role().name(),
                result.onboardingRequired(),
                result.onboardingVersion(),
                result.latestOnboardingVersion(),
                result.profile() == null ? null : Profile.from(result.profile())
        );
    }
}
