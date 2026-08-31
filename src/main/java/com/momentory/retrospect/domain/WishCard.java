package com.momentory.retrospect.domain;

import java.util.List;

/**
 * 바람 카드 — 감정 탐색을 거친 세션에서만 만든다 (채팅흐름_v2 Phase 4 · §5, 결정 ①로 기존 행동 카드 대체).
 *
 * <p>사용자가 {@code 아직 잘 모르겠어요}/{@code 오늘은 여기까지}로 비운 항목은 임의로 채우지 않는다 —
 * {@code desiredState}·{@code smallAction} 은 null 을 허용하고, 화면이 "아직 정하지 않음"으로 표시한다.
 * {@code sentiment} 는 감정 성격에 따른 항목 라벨 분기에 쓴다.
 *
 * @param situation    상황 — 감정이 생긴 핵심 장면(일기 작성의 핵심 event 요약)
 * @param emotions     확인된 감정 (최대 2)
 * @param needs        내 마음이 바랐던 것 / 채워준 것 (최대 2)
 * @param desiredState 바랐던 모습 — 상대방·상황에서 바랐던 구체적 모습. 없으면 null
 * @param smallAction  작은 행동 — 부담 없이 해볼 수 있는 것. 없으면 null
 */
public record WishCard(
        String situation,
        List<Emotion> emotions,
        List<Need> needs,
        String desiredState,
        String smallAction,
        WishSentiment sentiment) {

    public WishCard {
        emotions = emotions == null ? List.of() : List.copyOf(emotions);
        needs = needs == null ? List.of() : List.copyOf(needs);
    }

    /** 확인된 감정으로 성격을 도출해 카드를 만든다. */
    public static WishCard of(String situation, List<Emotion> emotions, List<Need> needs,
            String desiredState, String smallAction) {
        return new WishCard(situation, emotions, needs, desiredState, smallAction,
                WishSentiment.of(emotions == null ? List.of() : emotions));
    }
}
