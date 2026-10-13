package com.momentory.worry.presentation;

import com.momentory.worry.application.WorryResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;

public record WorryResponse(
        @Schema(description = "걱정 id", example = "1") Long id,
        @Schema(description = "속한 하루(KST · 04:00 경계)", example = "2026-10-13") LocalDate date,
        @Schema(description = "맡겨둔 걱정 한 줄", example = "내일 발표가 잘 안될까 봐 걱정돼요") String content,
        @Schema(description = "맡긴 시각", example = "2026-10-13T11:23:47.850Z") Instant createdAt
) {

    static WorryResponse from(WorryResult result) {
        return new WorryResponse(result.id(), result.date(), result.content(), result.createdAt());
    }
}
