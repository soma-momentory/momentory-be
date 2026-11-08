package com.momentory.notification.presentation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ReadNotificationsRequest(
        @NotNull(message = "잘못된 요청입니다.")
        @Size(min = 1, max = 50, message = "잘못된 요청입니다.")
        List<@NotNull(message = "잘못된 요청입니다.")
             @Size(max = 19, message = "잘못된 요청입니다.")
             @Pattern(regexp = "[0-9]+", message = "잘못된 요청입니다.") String> ids
) {
}
