package com.momentory.retrospect.domain;

import java.util.Collection;

/**
 * 바람 카드의 감정 성격 — 워딩 분기 (채팅흐름_v2 Phase 4 · 바람 카드 규칙).
 *
 * <p>불편한 감정이면 "내 마음이 바랐던 것", 긍정적인 감정이면 "내 마음을 채워준 것"으로 항목 라벨이
 * 갈린다. 확인된 감정 중 하나라도 불편하면 불편으로 본다(불편이 우선 — 대개 그 마음을 돌보는 게 목적).
 */
public enum WishSentiment {

    UNCOMFORTABLE,
    POSITIVE;

    /** 확인된 감정들로 성격을 정한다 — 비었거나 불편이 섞였으면 UNCOMFORTABLE. */
    public static WishSentiment of(Collection<Emotion> confirmedEmotions) {
        if (confirmedEmotions == null || confirmedEmotions.isEmpty()) {
            return UNCOMFORTABLE;
        }
        return confirmedEmotions.stream().allMatch(Emotion::isPositive)
                ? POSITIVE
                : UNCOMFORTABLE;
    }

    public boolean isPositive() {
        return this == POSITIVE;
    }

    /** 직렬화용 키 — "positive" / "uncomfortable". */
    public String key() {
        return this == POSITIVE ? "positive" : "uncomfortable";
    }

    /** "내 마음이 바랐던 것"(불편) / "내 마음을 채워준 것"(긍정). */
    public String wishLabel() {
        return this == POSITIVE ? "내 마음을 채워준 것" : "내 마음이 바랐던 것";
    }
}
