package com.momentory.actioncard.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import com.momentory.actioncard.domain.ActionCard;

/**
 * 바람 카드(v2, 기존 행동 카드 대체) 조회 결과 한 벌 — 보관함 목록·단건 응답의 재료다.
 * {@code retrospectId} 로 그날의 일기로 되돌아간다.
 *
 * <p>{@code emotions}(감정 키)·{@code needWords}(바람 단어)는 저장된 CSV 를 나눈 것이다 — 뜻·라벨은
 * 표현 계층이 역참조한다. {@code done}/{@code doneAt}/{@code reflection} 은 "해봤어요" 이후에만 채워진다.
 */
public record ActionCardView(Long id, Long retrospectId, String situation, String targetAction,
        List<String> emotions, List<String> needWords, String desiredState, String sentiment,
        LocalDate createdDate, boolean done, Instant doneAt, String reflection, Instant createdAt) {

    public ActionCardView {
        emotions = emotions == null ? List.of() : List.copyOf(emotions);
        needWords = needWords == null ? List.of() : List.copyOf(needWords);
    }

    static ActionCardView from(ActionCard card) {
        return new ActionCardView(card.getId(), card.getRetrospectId(), card.getSituation(),
                card.getTargetAction(), splitCsv(card.getEmotions()), splitCsv(card.getNeeds()),
                card.getDesiredState(), card.getSentiment(), card.getCreatedDate(), card.isDone(),
                card.getDoneAt(), card.getReflection(), card.getCreatedAt());
    }

    /** CSV → 목록(비었으면 빈 목록). */
    static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
