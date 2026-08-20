package com.momentory.actioncard.presentation;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.momentory.actioncard.application.ActionCardView;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * "해봤어요"/되돌리기 결과 — 바뀐 상태만 돌려준다. {@code doneAt}/{@code reflection} 은 되돌린
 * 뒤엔 비어(생략) 있다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionCardCompletionResponse(
        @Schema(description = "행동 카드 id", example = "1") Long id,
        @Schema(description = "해봤는지 여부", example = "true") boolean done,
        @Schema(description = "해본 시각 — 해본 뒤에만", example = "2026-08-12T09:20:11Z") Instant doneAt,
        @Schema(description = "느낀 점 — 해본 뒤에만") String reflection) {

    static ActionCardCompletionResponse from(ActionCardView view) {
        return new ActionCardCompletionResponse(view.id(), view.done(), view.doneAt(),
                view.reflection());
    }
}
