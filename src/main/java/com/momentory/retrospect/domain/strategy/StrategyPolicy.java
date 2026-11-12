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

    /**
     * v2 (2026-11-12): 수정 반영 뒤에도 한 단계 깊은 질문을 붙인다 — v1 은 공감·반영 뒤에만 붙여서, 수정 반영 다음에
     * 공감·반영이 오면 질문 없는 턴이 두 번 이어졌다.
     *
     * <p>v3 (2026-11-12): 기본은 매 턴 인정 뒤에 질문 하나다(설계 문서 4-3 · 4-8). 질문 없는 응답은 들어 달라는 요청,
     * 저항 표지가 나온 턴, 저항이 반복될 때의 빈도 조절로 한정한다. 5단계 이상에서도 방금 스스로 짚은 것을 한 번 더 묻고,
     * 수정 반영도 같은 턴에 질문을 잇는다. 짧은 동의에는 공감을 되풀이하지 않는다. v1 · v2 에서는 5턴 중 두 턴이
     * 「맞아」로 소모됐다.
     *
     * <p>v4 (2026-11-12): 사용자가 답하기 어려워하면("그냥 전부", "모르겠어") 같은 것을 다시 묻거나 쉽게 바꿔 묻지 않고
     * 다른 기억으로 넘어간다. 이것은 저항으로 세지 않는다 — 안 하겠다가 아니라 못 하겠다이고, 대개 질문이 어려웠던 탓이다.
     * 감정이 아직 나오지 않았으면 깊이 판단과 상관없이 자기 쪽 질문부터 한다. v3 에서는 감정을 말하기 전에
     * "어떤 점이 그렇게 느껴지게 했나요"가 나갔고, 답하기 어려워하는데 같은 질문을 되풀이했다.
     */
    public static final String VERSION = "v4";

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
        // 답하려 했지만 어려워함 — 저항으로 세지 않는다.
        boolean struggled = "struggle_to_answer".equals(reaction);

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

        // 짧은 동의 — 체험 깊이를 '판단 불가'라고 확신 있게 본 답변.
        ItemJudgment depthJudgment = judgment.of(JudgmentItem.EXPERIENCING_DEPTH);
        // 답하기 어려워한 답은 짧아도 그대로 받아준 뒤 넘어간다 — 받아주는 말을 건너뛰지 않는다.
        boolean brief = !struggled && "undeterminable".equals(depthJudgment.choice())
                && depthJudgment.confidence() >= minConfidence;
        TurnStrategy strategy = choose(judgment, now, reaction, resisted, depth,
                struggled || (before.topicDepth() >= 5 && before.lastAsked()), brief);
        // 사건은 한 소재에서 한 번만 묻는다 — 일부만 들었어도 같은 것을 되묻지 않는다.
        boolean eventAsked = strategy.focus() == QuestionFocus.EVENT;
        StrategySignals after = new StrategySignals(now.resistanceCount(), now.explicitMode(),
                now.emotionOut(), now.eventOut() || eventAsked, now.topicDepth(), now.topicStall(), strategy.type(),
                strategy.asks(), strategy.asks() ? 0 : now.turnsSinceQuestion() + 1);
        return new Decision(strategy,
                strategy.focus() == QuestionFocus.OTHER_TOPIC ? after.nextTopic() : after);
    }

    /**
     * @param depthNow          방금 답변의 체험 단계. 판단할 수 없었으면 0
     * @param leaveTopic        이 소재를 떠날 때인가 — 답하기 어려워했거나, 5단계 이상에 닿아 한 번 더 묻기까지 했다
     * @param brief             방금 답변이 짧은 동의뿐인가
     */
    private TurnStrategy choose(TurnJudgment judgment, StrategySignals s, String reaction, boolean resisted,
            int depthNow, boolean leaveTopic, boolean brief) {
        // ── 1. 먼저 적용하는 제약 ──
        ItemJudgment end = judgment.of(JudgmentItem.END_INTENT);
        if ("end_request".equals(end.choice()) && end.confidence() >= closeConfidence) {
            return noQuestion(StrategyType.CLOSE);
        }
        boolean corrected = "correct_interpretation".equals(reaction);
        if (StrategySignals.LISTEN.equals(s.explicitMode())) {
            // 들어 달라고 한 사용자에게는 묻지 않는다 — 고쳐 말한 것만 받아준다.
            return new TurnStrategy(corrected ? StrategyType.CORRECT : StrategyType.REFLECT,
                    QuestionFocus.NONE, false, brief);
        }

        // ── 2. 유형 ── 기본은 매 턴 인정 뒤에 질문 하나다. 받아주기만 하고 끝내면 대화가 멈춘다.
        int depth = depthNow > 0 ? depthNow : s.topicDepth();
        StrategyType type;
        QuestionFocus focus;
        if (StrategySignals.OTHER_VIEW.equals(s.explicitMode())) {
            type = StrategyType.REFRAME;
            focus = QuestionFocus.EVIDENCE;
        } else if (corrected) {
            // 고친 표현을 받은 뒤 같은 턴에 이어서 묻는다. 방금 고쳐 말한 소재에서 다른 기억으로 넘어가지는 않는다.
            type = StrategyType.CORRECT;
            focus = s.eventOut() ? deeper(depth, s.emotionOut()) : QuestionFocus.EVENT;
        } else if (leaveTopic || s.topicStall() >= TOPIC_STALL_LIMIT) {
            // 답하기 어려워했거나, 스스로 짚은 것까지 한 번 더 물었거나, 이 소재에서 더 깊어지지 않는다 → 다른 기억으로.
            type = StrategyType.EXPLORE;
            focus = QuestionFocus.OTHER_TOPIC;
        } else if (!s.eventOut()) {
            type = StrategyType.EXPLORE;
            focus = QuestionFocus.EVENT;
        } else {
            type = StrategyType.EXPLORE;
            focus = deeper(depth, s.emotionOut());
        }

        // ── 3. 질문 빈도 ──
        boolean sparse = s.resistanceCount() >= resistanceThreshold;
        if (resisted || (sparse && s.turnsSinceQuestion() < SPARSE_QUESTION_GAP)) {
            return new TurnStrategy(type, QuestionFocus.NONE, false, brief);
        }
        return new TurnStrategy(type, focus, sparse && focus != QuestionFocus.OTHER_TOPIC, brief);
    }

    /**
     * 4. 질문 깊이 — 지금 단계보다 한 단계 깊게. 단계를 모르면 가장 얕은 곳에서 시작한다.
     * 감정이 아직 나오지 않았으면 단계가 어떻게 판단됐든 자기 쪽 질문부터 한다 — 받을 감정이 없는데 그 감정을 물을 수 없다.
     */
    private static QuestionFocus deeper(int depth, boolean emotionOut) {
        if (!emotionOut) {
            return QuestionFocus.SELF;
        }
        if (depth >= 5) {
            return QuestionFocus.FOLLOW_UP;
        }
        if (depth >= 4) {
            return QuestionFocus.MEANING;
        }
        return depth == 3 ? QuestionFocus.FEELING : QuestionFocus.SELF;
    }

    private static TurnStrategy noQuestion(StrategyType type) {
        return new TurnStrategy(type, QuestionFocus.NONE, false, false);
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
