package com.momentory.schedule.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record ScheduleVisibilityRequest(
        @Schema(example = "true")
        @NotNull(message = "hidden은 필수입니다.")
        Boolean hidden
) {
}
