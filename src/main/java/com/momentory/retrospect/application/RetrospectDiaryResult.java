package com.momentory.retrospect.application;

import java.time.LocalDate;

import com.momentory.retrospect.infrastructure.persistence.ActionCard;
import com.momentory.retrospect.infrastructure.persistence.Retrospect;

/**
 * 완료된 회고 한 벌 — 일기 + (있으면) 행동 카드. 조회 응답의 재료다.
 *
 * <p>{@code card} 는 방향에 따라 없을 수 있다(짧은 기록형은 일기만 남는다).
 */
public record RetrospectDiaryResult(String diary, String reframedDiary, ActionCardView card) {

    public static RetrospectDiaryResult from(Retrospect retrospect, ActionCard card) {
        return new RetrospectDiaryResult(retrospect.getDiary(), retrospect.getReframedDiary(),
                card == null ? null : ActionCardView.from(card));
    }

    /** 행동 카드 조회 뷰 — {@code done}/{@code reflection} 은 "해봤어요" 이후에만 채워진다. */
    public record ActionCardView(Long id, String situation, String targetAction,
            LocalDate createdDate, boolean done, String reflection) {

        static ActionCardView from(ActionCard card) {
            return new ActionCardView(card.getId(), card.getSituation(), card.getTargetAction(),
                    card.getCreatedDate(), card.isDone(), card.getReflection());
        }
    }
}
