package com.momentory.retrospect.domain.strategy;

/**
 * 이번 대화에서 지금까지 관찰한 신호 — 턴별 판단을 세어 만든다. 다음 턴의 전략 선택에 쓰고, 회고 상태와 함께 저장된다.
 *
 * @param resistanceCount    바바의 질문·해석 뒤 답하지 않음·화제 전환·반박이 나온 횟수
 * @param explicitMode       가장 최근에 명시적으로 요청한 응답 방식(LISTEN·EXPLORE·OTHER_VIEW). 없으면 null
 * @param emotionOut         감정을 분명히 표현한 적이 있는가(현재 소재)
 * @param eventOut           사건을 구체적으로 설명한 적이 있는가(현재 소재)
 * @param topicDepth         현재 소재에서 도달한 가장 깊은 체험 단계(2~5). 아직 모르면 0
 * @param topicStall         현재 소재에서 깊이가 오르지 않은 채 이어진 턴 수
 * @param lastType           직전 턴의 전략 유형. 없으면 null
 * @param lastAsked          직전 턴에 질문을 붙였는가
 * @param turnsSinceQuestion 마지막 질문 뒤로 지난 턴 수
 */
public record StrategySignals(int resistanceCount, String explicitMode, boolean emotionOut,
        boolean eventOut, int topicDepth, int topicStall, StrategyType lastType, boolean lastAsked,
        int turnsSinceQuestion) {

    public static final String LISTEN = "LISTEN";
    public static final String EXPLORE = "EXPLORE";
    public static final String OTHER_VIEW = "OTHER_VIEW";

    public static StrategySignals initial() {
        return new StrategySignals(0, null, false, false, 0, 0, null, false, 0);
    }

    /** 다른 소재로 넘어간다 — 소재에 묶인 신호만 비운다. 저항 횟수와 요청한 응답 방식은 대화 전체의 것이다. */
    public StrategySignals nextTopic() {
        return new StrategySignals(resistanceCount, explicitMode, false, false, 0, 0, lastType,
                lastAsked, turnsSinceQuestion);
    }
}
