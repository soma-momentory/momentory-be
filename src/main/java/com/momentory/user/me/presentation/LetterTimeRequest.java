package com.momentory.user.me.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public record LetterTimeRequest(
        @Schema(description = "편지 알림 시각", example = "08:30")
        @NotBlank(message = "letterTime은 필수입니다.")
        @Pattern(regexp = "^(?:[01]\\d|2[0-3]):[0-5]\\d$", message = "letterTime은 HH:mm 형식이어야 합니다.")
        String letterTime,
        @Schema(description = "편지 알림 켜기 여부. 「알림 없이 볼게요」면 false", example = "true")
        @NotNull(message = "notificationEnabled는 필수입니다.")
        Boolean notificationEnabled
) {

    public LocalTime toLetterTime() {
        return LocalTime.parse(letterTime, DateTimeFormatter.ofPattern("HH:mm"));
    }
}
