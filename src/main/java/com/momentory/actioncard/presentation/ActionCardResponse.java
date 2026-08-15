package com.momentory.actioncard.presentation;

import java.time.Instant;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.momentory.actioncard.application.ActionCardView;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 행동 카드 한 장 조회 응답 — 보관함 목록의 항목이자 단건 조회의 본체. 프론트의 상황/목표 행동/
 * 느낀 점 칸에 그대로 대응한다.
 *
 * <p>{@code doneAt}/{@code reflection} 은 "해봤어요" 이후에만 채워진다(그 전엔 생략). 그날의
 * 일기는 {@code retrospectId} 로 {@code GET /retrospect/{id}/diary} 에서 가져온다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionCardResponse(
        @Schema(description = "행동 카드 id", example = "1") Long id,
        @Schema(description = "그날의 회고 id — 일기 조회에 쓴다", example = "10") Long retrospectId,
        @Schema(description = "상황", example = "발표를 앞두고 긴장했던 상황") String situation,
        @Schema(description = "목표 행동", example = "떨리는 건 준비를 잘했다는 신호라고 생각하며 심호흡을 세 번 하고 시작하기") String targetAction,
        @Schema(description = "만든 날짜", example = "2026-08-10") LocalDate createdDate,
        @Schema(description = "해봤는지 여부", example = "false") boolean done,
        @Schema(description = "해본 시각 — 해본 뒤에만 채워짐", example = "2026-08-12T09:20:11Z") Instant doneAt,
        @Schema(description = "느낀 점 — 해본 뒤에만 채워짐") String reflection,
        @Schema(description = "생성 시각", example = "2026-08-10T02:23:47.850Z") Instant createdAt) {

    static ActionCardResponse from(ActionCardView view) {
        return new ActionCardResponse(view.id(), view.retrospectId(), view.situation(),
                view.targetAction(), view.createdDate(), view.done(), view.doneAt(),
                view.reflection(), view.createdAt());
    }
}
