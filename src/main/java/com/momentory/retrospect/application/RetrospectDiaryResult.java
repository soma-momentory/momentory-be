package com.momentory.retrospect.application;

import java.time.LocalDate;

import com.momentory.diary.application.DiaryView;
import com.momentory.retrospect.infrastructure.persistence.ActionCard;

/**
 * 완료된 회고 한 벌 — 일기 + (있으면) 행동 카드. 조회 응답의 재료다. 일기는 diary 컨텍스트의
 * 읽기 모델({@link DiaryView})로, 행동 카드는 retrospect 것으로 합쳐 만든다.
 *
 * <p>{@code diary} 는 안전 중단 등으로 일기가 남지 않은 회고면 없을 수 있고, {@code card} 는
 * 방향에 따라 없을 수 있다(짧은 기록형은 일기만 남는다).
 */
public record RetrospectDiaryResult(String diary, String reframedDiary, ActionCardView card) {

    public static RetrospectDiaryResult from(DiaryView diary, ActionCard card) {
        return new RetrospectDiaryResult(
                diary == null ? null : diary.original(),
                diary == null ? null : diary.reframed(),
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
