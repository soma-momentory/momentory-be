package com.momentory.user.me.presentation;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.momentory.user.me.application.UserMeService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalTime;

public record LetterTimeResponse(
        @Schema(description = "편지 알림 시각", example = "08:30") @JsonFormat(pattern = "HH:mm") LocalTime letterTime,
        @Schema(description = "편지 알림 켜기 여부", example = "true") boolean notificationEnabled
) {

    static LetterTimeResponse from(UserMeService.LetterPreference preference) {
        return new LetterTimeResponse(preference.letterTime(), preference.notificationEnabled());
    }
}
