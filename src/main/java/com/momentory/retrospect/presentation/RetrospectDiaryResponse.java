package com.momentory.retrospect.presentation;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.momentory.retrospect.application.RetrospectDiaryResult;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 완료된 회고의 일기 조회 응답 — 그날의 일기 화면이 이걸 받아 본문과 행동 카드를 그린다.
 *
 * <p>짧은 기록형은 {@code reframedDiary}·{@code actionCard} 가 없어 프론트가 본문만 그린다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RetrospectDiaryResponse(
        @Schema(description = "일기 본문") String diary,
        @Schema(description = "다시 본 오늘(리프레임) — 없을 수 있음") String reframedDiary,
        @Schema(description = "행동 카드 — 없을 수 있음") ActionCard actionCard) {

    static RetrospectDiaryResponse from(RetrospectDiaryResult result) {
        return new RetrospectDiaryResponse(result.diary(), result.reframedDiary(),
                ActionCard.from(result.card()));
    }

    /** 행동 카드 — 프론트의 상황/목표 행동/느낀 점 칸에 그대로 대응한다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActionCard(
            @Schema(example = "1") Long id,
            @Schema(example = "발표를 앞두고 긴장했던 상황") String situation,
            @Schema(example = "심호흡을 세 번 하고 시작하기") String targetAction,
            @Schema(example = "떨리는 건 준비를 잘했다는 신호라고 생각하기") String detail,
            @Schema(example = "2026-08-10") LocalDate createdDate,
            @Schema(example = "false") boolean done,
            @Schema(description = "느낀 점 — 해본 뒤에만 채워짐") String reflection) {

        static ActionCard from(RetrospectDiaryResult.ActionCardView card) {
            if (card == null) {
                return null;
            }
            return new ActionCard(card.id(), card.situation(), card.targetAction(), card.detail(),
                    card.createdDate(), card.done(), card.reflection());
        }
    }
}
