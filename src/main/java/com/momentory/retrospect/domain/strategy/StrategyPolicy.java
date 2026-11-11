package com.momentory.retrospect.domain.strategy;

import com.momentory.retrospect.domain.judgment.ItemJudgment;
import com.momentory.retrospect.domain.judgment.JudgmentItem;
import com.momentory.retrospect.domain.judgment.TurnJudgment;

/**
 * 턴 판단과 이번 대화의 신호로 이번 턴의 전략을 고른다 — 순수 규칙이고 모델을 부르지 않는다.
 *
 * <p>순서는 <b>제약 확인 → 유형 선택 → 질문 빈도 → 질문 깊이</b>다. 확신이 기준에 못 미치거나 '판단 불가'인
 * 항목은 이번 턴 선택에서 건너뛰고 신호에도 세지 않는다. 규칙을 고치면 {@link #VERSION} 을 올린다 —
 * 기록에 버전이 남아 규칙이 바뀌기 전후의 대화를 섞지 않고 볼 수 있다.
 *
 * <p>저장된 성향은 아직 없다. 기본값(바바가 이끌고, 지지 위주)에서 시작해 이번 대화의 신호만 반영한다.
 */
public class StrategyPolicy {

    public static final String VERSION = "v1";

    /** 한 소재에서 깊이가 이만큼 연달아 오르지 않으면 다른 기억으로 넘어간다. */
    static final int TOPIC_STALL_LIMIT = 2;
    /** 저항이 반복된 사용자에게는 이만큼 턴을 건너 한 번만 묻는다. */
    static final int SPARSE_QUESTION_GAP = 2;

    private final double minConfidence;
    private final double closeConfidence;
    private final int resistanceThreshold;

    /**
     * @param minConfidence       이 값 미만의 판단은 '판단 불가'로 본다
     * @param closeConfidence     종료 요청은 이 값 이상일 때만 바로 마무리한다(회고는 하루 한 번이라 되돌릴 수 없다)
     * @param resistanceThreshold 저항이 이 횟수부터 질문을 줄이고 선택지 형태로 묻는다
     */
    public StrategyPolicy(double minConfidence, double closeConfidence, int resistanceThreshold) {
        this.minConfidence = minConfidence;
        this.closeConfidence = closeConfidence;
        this.resistanceThreshold = resistanceThreshold;
    }

    /** 고른 전략과, 이번 턴까지 반영한 신호. */
    public record Decision(TurnStrategy strategy, StrategySignals signals) {
    }

    /**
     * @param firstTurn 일기 작성 채팅의 첫 답변인가 — 바바의 직전 말이 첫 질문뿐이라 '직전 응답에 대한 반응'을 보지 않는다
     */
    public Decision decide(TurnJudgment judgment, StrategySignals before, boolean firstTurn) {
        String reaction = firstTurn ? null : usable(judgment, JudgmentItem.REACTION);
        String requested = usable(judgment, JudgmentItem.REQUESTED_MODE);
        int depth = depthOf(usable(judgment, JudgmentItem.EXPERIENCING_DEPTH));
        boolean resisted = "no_answer_or_topic_change".equals(reaction) || "argue".equals(reaction);

        // ── 신호 갱신 ──
        int resistanceCount = before.resistanceCount() + (resisted ? 1 : 0);
        String explicitMode = before.explicitMode();
        if ("listen".equals(requested) || "refuse_question".equals(reaction)) {
            explicitMode = StrategySignals.LISTEN;
        } else if ("explore".equals(requested)) {
            explicitMode = StrategySignals.EXPLORE;
        } else if ("other_view".equals(requested)) {
            explicitMode = StrategySignals.OTHER_VIEW;
        }
        boolean emotionOut = before.emotionOut()
                || "clear".equals(usable(judgment, JudgmentItem.EMOTION_EXPRESSION));
        boolean eventOut = before.eventOut() || "specific".equals(usable(judgment, JudgmentItem.EVENT_INFO));
        int topicDepth = before.topicDepth();
        int topicStall = before.topicStall();
        if (depth > topicDepth) {
            topicDepth = depth;
            topicStall = 0;
        } else if (depth > 0 && before.lastAsked()) {
            // 질문을 받고도 깊어지지 않은 턴만 센다 — 받아주기만 한 턴 뒤에는 깊어질 계기가 없었다.
            topicStall++;
        }
        StrategySignals now = new StrategySignals(resistanceCount, explicitMode, emotionOut, eventOut,
                topicDepth, topicStall, before.lastType(), before.lastAsked(), before.turnsSinceQuestion());

        TurnStrategy strategy = choose(judgment, now, reaction, resisted, depth,
                before.topicDepth() >= 5 && before.lastType() == StrategyType.REFLECT && !before.lastAsked());
        // 사건은 한 소재에서 한 번만 묻는다 — 일부만 들었어도 같은 것을 되묻지 않는다.
        boolean eventAsked = strategy.focus() == QuestionFocus.EVENT;
        StrategySignals after = new StrategySignals(now.resistanceCount(), now.explicitMode(),
                now.emotionOut(), now.eventOut() || eventAsked, now.topicDepth(), now.topicStall(), strategy.type(),
                strategy.asks(), strategy.asks() ? 0 : now.turnsSinceQuestion() + 1);
        return new Decision(strategy,
                strategy.focus() == QuestionFocus.OTHER_TOPIC ? after.nextTopic() : after);
    }

    /**
     * @param depthNow         방금 답변의 체험 단계. 판단할 수 없었으면 0
     * @param deepestReflected 이 소재에서 이미 5단계 이상에 닿았고 직전 턴에 그것을 받아줬는가
     */
    private TurnStrategy choose(TurnJudgment judgment, StrategySignals s, String reaction, boolean resisted,
            int depthNow, boolean deepestReflected) {
        // ── 1. 먼저 적용하는 제약 ──
        ItemJudgment end = judgment.of(JudgmentItem.END_INTENT);
        if ("end_request".equals(end.choice()) && end.confidence() >= closeConfidence) {
            return noQuestion(StrategyType.CLOSE);
        }
        if ("correct_interpretation".equals(reaction)) {
            return noQuestion(StrategyType.CORRECT);
        }
        if (StrategySignals.LISTEN.equals(s.explicitMode())) {
            return noQuestion(StrategyType.REFLECT);
        }

        // ── 2. 유형 ──
        boolean reflectedLast = s.lastType() == StrategyType.REFLECT && !s.lastAsked();
        StrategyType type;
        QuestionFocus focus;
        if (StrategySignals.OTHER_VIEW.equals(s.explicitMode())) {
            type = StrategyType.REFRAME;
            focus = QuestionFocus.EVIDENCE;
        } else if (depthNow >= 5 && !deepestReflected) {
            // 스스로 탐색 중이면 질문 없이 받아준다.
            return noQuestion(StrategyType.REFLECT);
        } else if (deepestReflected || s.topicStall() >= TOPIC_STALL_LIMIT) {
            // 충분히 깊어져 받아주기까지 했거나, 이 소재에서 더 깊어지지 않는다 → 다른 기억으로.
            type = StrategyType.EXPLORE;
            focus = QuestionFocus.OTHER_TOPIC;
        } else if (!s.eventOut()) {
            type = StrategyType.EXPLORE;
            focus = QuestionFocus.EVENT;
        } else if (!s.emotionOut() || StrategySignals.EXPLORE.equals(s.explicitMode()) || reflectedLast) {
            // 감정이 아직 안 나왔거나, 탐색을 요청했거나, 직전에 받아주기만 했으면 한 단계 깊게 묻는다.
            type = StrategyType.EXPLORE;
            focus = deeper(depthNow > 0 ? depthNow : s.topicDepth());
        } else {
            // 감정과 사건이 다 나왔고 기본 성향(지지)이면 받아준다.
            return noQuestion(StrategyType.REFLECT);
        }

        // ── 3. 질문 빈도 ──
        boolean sparse = s.resistanceCount() >= resistanceThreshold;
        if (resisted || (sparse && s.turnsSinceQuestion() < SPARSE_QUESTION_GAP)) {
            return new TurnStrategy(type, QuestionFocus.NONE, false);
        }
        return new TurnStrategy(type, focus, sparse && focus != QuestionFocus.OTHER_TOPIC);
    }

    /** 4. 질문 깊이 — 지금 단계보다 한 단계 깊게. 단계를 모르면 가장 얕은 곳에서 시작한다. */
    private static QuestionFocus deeper(int depth) {
        if (depth >= 4) {
            return QuestionFocus.MEANING;
        }
        return depth == 3 ? QuestionFocus.FEELING : QuestionFocus.SELF;
    }

    private static TurnStrategy noQuestion(StrategyType type) {
        return new TurnStrategy(type, QuestionFocus.NONE, false);
    }

    /** 확신이 기준 이상이고 '판단 불가'가 아닌 선택지만 돌려준다. 아니면 null. */
    private String usable(TurnJudgment judgment, JudgmentItem item) {
        ItemJudgment j = judgment.of(item);
        if (j.confidence() < minConfidence || "undeterminable".equals(j.choice())) {
            return null;
        }
        return j.choice();
    }

    private static int depthOf(String choice) {
        if (choice == null) {
            return 0;
        }
        return switch (choice) {
            case "stage_1_2" -> 2;
            case "stage_3" -> 3;
            case "stage_4" -> 4;
            case "stage_5_plus" -> 5;
            default -> 0;
        };
    }
}
