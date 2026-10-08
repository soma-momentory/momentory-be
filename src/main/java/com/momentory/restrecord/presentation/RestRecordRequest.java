package com.momentory.restrecord.presentation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.momentory.restrecord.domain.RestContent;
import com.momentory.restrecord.domain.RestMood;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record RestRecordRequest(
        @Schema(description = "어떤 쉼터인지", example = "WORRY_BOX")
        @NotNull(message = "쉼터를 입력해주세요.")
        RestContent content,
        @Schema(description = "끝까지 했는지", example = "true")
        @NotNull(message = "끝까지 했는지 입력해주세요.")
        Boolean completed,
        @Schema(description = "마칠 때 고른 기분 — 끝까지 했을 때만", nullable = true, example = "BETTER")
        RestMood mood
) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "끝까지 했을 때는 기분을 골라주세요.")
    public boolean isCompletedMoodPresent() {
        return completed == null || !completed || mood != null;
    }

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "끝까지 하지 않았을 때는 기분을 남길 수 없습니다.")
    public boolean isIncompleteMoodAbsent() {
        return completed == null || completed || mood == null;
    }
}
