package com.momentory.restrecord.presentation;

import com.momentory.restrecord.application.RestRecordResult;
import com.momentory.restrecord.domain.RestContent;
import com.momentory.restrecord.domain.RestMood;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;

public record RestRecordResponse(
        @Schema(description = "이용 기록 id", example = "1") Long id,
        @Schema(description = "어떤 쉼터인지", example = "WORRY_BOX") RestContent content,
        @Schema(description = "속한 하루(KST · 04:00 경계)", example = "2026-10-08") LocalDate date,
        @Schema(description = "끝까지 했는지", example = "true") boolean completed,
        @Schema(description = "마칠 때 고른 기분 — 끝까지 하지 않았으면 null", nullable = true, example = "BETTER") RestMood mood,
        @Schema(description = "남긴 시각", example = "2026-10-08T11:23:47.850Z") Instant createdAt
) {

    static RestRecordResponse from(RestRecordResult result) {
        return new RestRecordResponse(result.id(), result.content(), result.date(), result.completed(), result.mood(), result.createdAt());
    }
}
