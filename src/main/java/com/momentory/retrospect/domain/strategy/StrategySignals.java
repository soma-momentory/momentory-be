package com.momentory.retrospect.domain.strategy;

/**
 * 이번 대화에서 지금까지 관찰한 것 중 다음 턴의 방침에 쓰는 것. 회고 상태와 함께 저장된다.
 *
 * <p>감정 · 사건 · 체험 깊이는 여기에 두지 않는다 — 그것들은 방침을 고르는 데 쓰지 않는 측정값이고, 턴별 판단 기록에 남는다.
 * 예전에 저장된 회고 상태에는 없는 값이 있어 일부는 비어 있을 수 있는 형으로 받고, 없으면 비어 있는 것으로 읽는다.
 *
 * @param resistanceCount    바바의 질문·해석 뒤 답하지 않음·화제 전환·반박이 나온 횟수 — 질문 빈도를 줄이는 데 쓴다
 * @param yieldStreak        사용자가 바바의 말을 받아들이지 않은 턴이 지금까지 연달아 이어진 횟수
 * @param explicitMode       가장 최근에 명시적으로 요청한 응답 방식(LISTEN·OTHER_VIEW). 없으면 null
 * @param topicNumber        지금 몇 번째 이야기인가(1부터)
 * @param wrapUpProposed     지금 이야기에서 마무리를 이미 제안했는가
 * @param lastType           직전 턴의 방침. 없으면 null
 * @param turnsSinceQuestion 마지막 질문이나 마무리 제안 뒤로 지난 턴 수
 * @param questionsAsked     이 대화에서 바바가 한 질문 수(첫 질문은 세지 않는다)
 * @param questionsAfterFeeling 지금 이야기에서 그때의 마음을 들은 뒤로 바바가 한 질문 수. 아직 마음을 듣지 못했으면 null
 * @param deepestStage 지금 이야기에서 가장 깊었던 체험 깊이(1~2단계=1 … 5단계 이상=4). 판단된 적이 없으면 null
 */
public record StrategySignals(int resistanceCount, Integer yieldStreak, String explicitMode, Integer topicNumber,
        Boolean wrapUpProposed, StrategyType lastType, int turnsSinceQuestion, Integer questionsAsked,
        Integer questionsAfterFeeling, Integer deepestStage) {

    public static final String LISTEN = "LISTEN";
    public static final String OTHER_VIEW = "OTHER_VIEW";

    public StrategySignals {
        yieldStreak = yieldStreak == null ? 0 : yieldStreak;
        topicNumber = topicNumber == null ? 1 : topicNumber;
        wrapUpProposed = wrapUpProposed != null && wrapUpProposed;
        questionsAsked = questionsAsked == null ? 0 : questionsAsked;
    }

    /** 지금 이야기에서 아직 마음을 듣지 못한 신호. */
    public StrategySignals(int resistanceCount, Integer yieldStreak, String explicitMode, Integer topicNumber,
            Boolean wrapUpProposed, StrategyType lastType, int turnsSinceQuestion, Integer questionsAsked) {
        this(resistanceCount, yieldStreak, explicitMode, topicNumber, wrapUpProposed, lastType, turnsSinceQuestion,
                questionsAsked, null, null);
    }

    public StrategySignals withQuestionsAfterFeeling(Integer count) {
        return withFollowUp(count, deepestStage);
    }

    /** 지금 이야기에서 마음을 들은 뒤의 질문 수와 가장 깊었던 단계를 바꾼다. */
    public StrategySignals withFollowUp(Integer count, Integer deepest) {
        return new StrategySignals(resistanceCount, yieldStreak, explicitMode, topicNumber, wrapUpProposed, lastType,
                turnsSinceQuestion, questionsAsked, count, deepest);
    }

    public static StrategySignals initial() {
        return new StrategySignals(0, 0, null, 1, false, null, 0, 0);
    }
}
