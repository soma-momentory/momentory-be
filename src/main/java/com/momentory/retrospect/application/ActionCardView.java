package com.momentory.retrospect.application;

import java.time.Instant;
import java.time.LocalDate;

import com.momentory.retrospect.infrastructure.persistence.ActionCard;

/**
 * 행동 카드 조회 결과 한 벌 — 보관함 목록·단건 응답의 재료다. {@code retrospectId} 로 그날의
 * 일기({@code GET /retrospect/{id}/diary})로 되돌아간다.
 *
 * <p>{@code done}/{@code doneAt}/{@code reflection} 은 "해봤어요" 이후에만 채워진다.
 */
public record ActionCardView(Long id, Long retrospectId, String situation, String targetAction,
        LocalDate createdDate, boolean done, Instant doneAt, String reflection, Instant createdAt) {

    static ActionCardView from(ActionCard card) {
        return new ActionCardView(card.getId(), card.getRetrospectId(), card.getSituation(),
                card.getTargetAction(), card.getCreatedDate(), card.isDone(), card.getDoneAt(),
                card.getReflection(), card.getCreatedAt());
    }
}
