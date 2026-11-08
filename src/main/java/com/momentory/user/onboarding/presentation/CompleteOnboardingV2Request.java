package com.momentory.user.onboarding.presentation;

import com.momentory.user.domain.Gender;
import com.momentory.user.domain.OnboardingResponse;
import com.momentory.user.domain.OnboardingResponse.Goal;
import com.momentory.user.domain.UserProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

public record CompleteOnboardingV2Request(
        @Schema(description = "닉네임", example = "하늘")
        @NotBlank(message = "nickname은 필수입니다.")
        @Size(max = UserProfile.NICKNAME_MAX_LENGTH, message = "nickname은 최대 10자입니다.")
        String nickname,
        @Schema(description = "나이. 건너뛰면 null", example = "25", nullable = true)
        @Min(value = 1, message = "age는 1 이상이어야 합니다.")
        @Max(value = 120, message = "age는 120 이하여야 합니다.")
        Integer age,
        @Schema(description = "성별. 건너뛰면 UNSPECIFIED", example = "FEMALE")
        @NotNull(message = "gender는 필수입니다.")
        Gender gender,
        @Schema(description = "회고 시간", example = "22:30")
        @NotBlank(message = "reflectionTime은 필수입니다.")
        @Pattern(regexp = "^(?:[01]\\d|2[0-3]):[0-5]\\d$", message = "reflectionTime은 HH:mm 형식이어야 합니다.")
        String reflectionTime,
        @Schema(description = "푸시 알림 수신 선호 여부", example = "true")
        @NotNull(message = "notificationEnabled는 필수입니다.")
        Boolean notificationEnabled,
        @Schema(description = "프론트가 보고한 캘린더 연동 성공 여부", example = "false")
        @NotNull(message = "calendarIntegrationEnabled는 필수입니다.")
        Boolean calendarIntegrationEnabled,
        @Schema(description = "하루에 기분이 몇 번쯤 바뀌나요? 건너뛰면 null", example = "FEW_TIMES", nullable = true)
        OnboardingResponse.MoodChange moodChange,
        @Schema(description = "내 기분이 왜 그런지, 잘 아는 편인가요? 건너뛰면 null", example = "SOMETIMES", nullable = true)
        OnboardingResponse.KnowWhy knowWhy,
        @Schema(description = "하루가 끝나면, 주로 어떤 편인가요? 건너뛰면 null", example = "TIRED_SLEEP", nullable = true)
        OnboardingResponse.DayEnd dayEnd,
        @Schema(description = "일기를 써 본 적 있나요? 건너뛰면 null", example = "STOPPED", nullable = true)
        OnboardingResponse.DiaryHistory diaryHistory,
        @Schema(description = "바바와 해 보고 싶은 것. 비어도 된다", example = "[\"KNOW_WHEN_TIRED\"]")
        @NotNull(message = "goals는 필수입니다.")
        Set<@NotNull(message = "goals에는 null을 포함할 수 없습니다.") Goal> goals
) {

    public CompleteOnboardingV2Request {
        nickname = nickname == null ? null : nickname.strip();
    }

    public LocalTime toReflectionTime() {
        return LocalTime.parse(reflectionTime, DateTimeFormatter.ofPattern("HH:mm"));
    }
}
