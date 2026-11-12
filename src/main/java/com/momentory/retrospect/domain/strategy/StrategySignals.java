package com.momentory.retrospect.domain.strategy;

/**
 * 이번 대화에서 지금까지 관찰한 신호 — 턴별 판단을 세어 만든다. 다음 턴의 전략 선택에 쓰고, 회고 상태와 함께 저장된다.
 *
 * @param resistanceCount    바바의 질문·해석 뒤 답하지 않음·화제 전환·반박이 나온 횟수
 * @param resistanceStreak   그런 저항이 지금까지 연달아 나온 횟수(저항이 아닌 턴이 오면 0)
 * @param explicitMode       가장 최근에 명시적으로 요청한 응답 방식(LISTEN·EXPLORE·OTHER_VIEW). 없으면 null
 * @param emotionOut         감정을 분명히 표현한 적이 있는가(현재 소재)
 * @param eventOut           사건을 구체적으로 설명한 적이 있는가(현재 소재)
 * @param topicNumber        지금 몇 번째 소재인가(1부터). 다른 기억으로 넘어갈 때마다 오른다
 * @param startDepth         이 대화에서 처음 판단된 체험 단계(2~5). 아직 모르면 0
 * @param topicDepth         현재 소재에서 도달한 가장 깊은 체험 단계(2~5). 아직 모르면 0
 * @param topicStall         현재 소재에서 질문하고도 깊이가 오르지 않은 채 이어진 턴 수
 * @param wrapUpProposed     현재 소재에서 마무리를 이미 제안했는가 — 더 말하겠다고 했으면 같은 소재에서 다시 제안하지 않는다
 * @param lastType           직전 턴의 전략 유형. 없으면 null
 * @param lastFocus          직전 턴 질문의 초점. 없으면 null
 * @param turnsSinceQuestion 마지막 질문 뒤로 지난 턴 수
 * @param questionsAsked     이 대화에서 바바가 한 질문 수(첫 질문은 세지 않는다)
 */
public record StrategySignals(int resistanceCount, Integer resistanceStreak, String explicitMode,
        boolean emotionOut, boolean eventOut, Integer topicNumber, Integer startDepth, int topicDepth,
        int topicStall,
        Boolean wrapUpProposed, StrategyType lastType, QuestionFocus lastFocus, int turnsSinceQuestion,
        Integer questionsAsked) {

    /**
     * 나중에 더한 값(연달은 저항 · 소재 번호 · 시작 깊이 · 마무리 제안 여부 · 질문 수)은 예전에 저장된 회고 상태에는 없다 —
     * 없으면 비어 있는 것으로 읽는다.
     */
    public StrategySignals {
        resistanceStreak = resistanceStreak == null ? 0 : resistanceStreak;
        topicNumber = topicNumber == null ? 1 : topicNumber;
        startDepth = startDepth == null ? 0 : startDepth;
        wrapUpProposed = wrapUpProposed != null && wrapUpProposed;
        questionsAsked = questionsAsked == null ? 0 : questionsAsked;
    }

    public static final String LISTEN = "LISTEN";
    public static final String EXPLORE = "EXPLORE";
    public static final String OTHER_VIEW = "OTHER_VIEW";

    public static StrategySignals initial() {
        return new StrategySignals(0, 0, null, false, false, 1, 0, 0, 0, false, null, null, 0, 0);
    }

    /** 직전 턴에 질문을 했는가 — 마무리 제안은 깊이를 묻는 질문이 아니다. */
    public boolean lastAsked() {
        return lastFocus != null && lastFocus != QuestionFocus.NONE && lastFocus != QuestionFocus.WRAP_UP;
    }

    /** 다른 소재로 넘어간다 — 소재에 묶인 신호만 비운다. 저항·요청한 응답 방식·질문 수는 대화 전체의 것이다. */
    public StrategySignals nextTopic() {
        return new StrategySignals(resistanceCount, resistanceStreak, explicitMode, false, false, topicNumber + 1,
                startDepth, 0, 0, false, lastType, lastFocus, turnsSinceQuestion, questionsAsked);
    }
}
