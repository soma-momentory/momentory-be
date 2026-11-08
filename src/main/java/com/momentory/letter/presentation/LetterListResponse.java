package com.momentory.letter.presentation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.momentory.letter.application.LetterView;

import io.swagger.v3.oas.annotations.media.Schema;

public record LetterListResponse(
        @Schema(description = "열 수 있는 편지, 최신순. 내용은 날짜별 조회로 한 통씩 불러온다") List<Item> letters
) {

    public record Item(
            @Schema(description = "편지 ID", example = "12") Long id,
            @Schema(description = "편지의 출처 일기 날짜", example = "2026-10-26") LocalDate date,
            @Schema(description = "받은 날 · 그다음 날이면 true", example = "true") boolean isNew,
            @Schema(description = "처음 연 시각. 아직 안 열었으면 null", nullable = true) Instant openedAt
    ) {
    }

    static LetterListResponse from(List<LetterView.Summary> summaries) {
        return new LetterListResponse(summaries.stream()
                .map(s -> new Item(s.id(), s.date(), s.isNew(), s.openedAt())).toList());
    }
}
