package com.momentory.treasure.presentation;

import com.momentory.treasure.application.TreasureResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;

public record TreasureResponse(
        @Schema(description = "보물 id", example = "1") Long id,
        @Schema(description = "속한 하루(KST · 04:00 경계)", example = "2026-10-05") LocalDate date,
        @Schema(description = "담은 순간 한 줄", example = "친구에게 먼저 연락했어요.") String content,
        @Schema(description = "담은 시각", example = "2026-10-05T11:23:47.850Z") Instant createdAt
) {

    static TreasureResponse from(TreasureResult result) {
        return new TreasureResponse(result.id(), result.date(), result.content(), result.createdAt());
    }
}
