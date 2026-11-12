package com.momentory.retrospect.domain.strategy;

import com.momentory.retrospect.domain.judgment.ItemJudgment;
import com.momentory.retrospect.domain.judgment.JudgmentItem;
import com.momentory.retrospect.domain.judgment.TurnJudgment;

/**
 * 턴 판단과 이번 대화의 신호로 이번 턴의 전략을 고른다 — 순수 규칙이고 모델을 부르지 않는다.
 *
 * <p>순서는 <b>제약 확인 → 마무리 제안 → 유형 선택 → 질문 빈도 → 질문 깊이</b>다(설계 문서 4번). 확신이 기준에 못
 * 미치거나 '판단 불가'인 항목은 이번 턴 선택에서 건너뛰고 신호에도 세지 않는다. 규칙을 고치면 {@link #VERSION} 을
 * 올린다 — 기록에 버전이 남아 규칙이 바뀌기 전후의 대화를 섞지 않고 볼 수 있다.
 *
 * <p>저장된 성향은 아직 없다. 기본값(바바가 이끌고, 지지 위주)에서 시작해 이번 대화의 신호만 반영한다.
 *
 * <p>버전 이력:
 * <ul>
 * <li>v1: 문서의 유형 정의 그대로 — 감정과 사건이 다 나오면 질문 없이 받아준다.
 * <li>v2: 수정 반영 뒤에도 한 단계 깊은 질문을 붙인다.
 * <li>v3: 기본은 매 턴 인정 뒤에 질문 하나다(문서 4-3 · 4-8). 5단계 이상에서도 한 번 더 묻고, 짧은 동의에는 공감을
 *     되풀이하지 않는다.
 * <li>v4: 답하기 어려워하면 다른 기억으로 넘어가고 저항으로 세지 않는다. 감정이 나오기 전에는 자기 쪽 질문부터 한다.
 * <li>v5: 턴 수 상한을 버리고 문서 4-7 로 간다 — 충분해지면 마무리를 제안하고(더 말하고 싶으면 이어간다), 질문 수에
 *     상한을 둔다. 직전 턴과 같은 초점을 연달아 묻지 않는다. 판단이 없는 턴은 기본값으로 간다(옛 고정 순서 폴백 없음).
 * <li>v6: 같은 초점이 다시 골라지면 더 어려운 질문으로 올리지 않고 그 소재를 떠난다 — 이미 이유까지 들은 뒤에 "이유가
 *     따로 있었나요"를 물어 「잘 모르겠어」가 나왔다. 소재는 둘까지다: 첫 소재면 다른 기억을 묻고, 둘째 소재면
 *     마무리를 제안한다.
 * </ul>
 */
public class StrategyPolicy {

    /** 한 대화에서 다루는 소재 수 — 사건은 둘까지 뽑는다. */
    static final int MAX_TOPICS = 2;

    public static final String VERSION = "v6";

    /** 한 소재에서 질문하고도 깊이가 이만큼 연달아 오르지 않으면 같은 것을 다시 묻게 된 것으로 본다. */
    static final int TOPIC_STALL_LIMIT = 2;
    /** 저항이 이만큼 연달아 나오면 마무리를 제안한다. */
    static final int RESISTANCE_STREAK_LIMIT = 2;
    /** 저항이 반복된 사용자에게는 이만큼 턴을 건너 한 번만 묻는다. */
    static final int SPARSE_QUESTION_GAP = 2;

    private final double minConfidence;
    private final double closeConfidence;
    private final int resistanceThreshold;
    private final int questionCap;

    /**
     * @param minConfidence       이 값 미만의 판단은 '판단 불가'로 본다
     * @param closeConfidence     종료 요청은 이 값 이상일 때만 바로 마무리한다(회고는 하루 한 번이라 되돌릴 수 없다)
     * @param resistanceThreshold 저항이 이 횟수부터 질문을 줄이고 선택지 형태로 묻는다
     * @param questionCap         바바의 질문이 이 수에 닿으면 더 묻지 않고 받아주기만 한다
     */
    public StrategyPolicy(double minConfidence, double closeConfidence, int resistanceThreshold, int questionCap) {
        this.minConfidence = minConfidence;
        this.closeConfidence = closeConfidence;
        this.resistanceThreshold = resistanceThreshold;
        this.questionCap = questionCap;
    }

    /** 고른 전략과, 이번 턴까지 반영한 신호. */
    public record Decision(TurnStrategy strategy, StrategySignals signals) {
    }

    /** 이번 턴 판단에서 전략 선택에 쓰는 것만 추린 것 — 쓸 수 없는 항목은 null 이나 0 이다. */
    private record Reading(boolean judged, boolean endRequested, String reaction, String requested,
            boolean emotionClear, boolean eventSpecific, int depth, boolean brief) {

        /** 판단이 없는 턴. */
        static Reading none(boolean endRequested) {
            return new Reading(false, endRequested, null, null, false, false, 0, false);
        }
    }

    /**
     * @param firstTurn 일기 작성 채팅의 첫 답변인가 — 바바의 직전 말이 첫 질문뿐이라 '직전 응답에 대한 반응'을 보지 않는다
     */
    public Decision decide(TurnJudgment judgment, StrategySignals before, boolean firstTurn) {
        ItemJudgment end = judgment.of(JudgmentItem.END_INTENT);
        ItemJudgment depth = judgment.of(JudgmentItem.EXPERIENCING_DEPTH);
        String reaction = firstTurn ? null : usable(judgment, JudgmentItem.REACTION);
        // 짧은 동의 — 체험 깊이를 '판단 불가'라고 확신 있게 본 답변. 답하기 어려워한 답은 짧아도 받아준 뒤 넘어간다.
        boolean brief = !"struggle_to_answer".equals(reaction) && "undeterminable".equals(depth.choice())
                && depth.confidence() >= minConfidence;
        return decide(new Reading(true,
                "end_request".equals(end.choice()) && end.confidence() >= closeConfidence,
                reaction,
                usable(judgment, JudgmentItem.REQUESTED_MODE),
                "clear".equals(usable(judgment, JudgmentItem.EMOTION_EXPRESSION)),
                "specific".equals(usable(judgment, JudgmentItem.EVENT_INFO)),
                depthOf(usable(judgment, JudgmentItem.EXPERIENCING_DEPTH)),
                brief), before, firstTurn);
    }

    /**
     * 판단이 없는 턴(판단 모델 실패) — 지금까지의 신호만으로 기본값을 고른다. 이 턴으로 신호를 바꾸지는 않는다.
     *
     * @param stopPhrase 사용자의 답이 「그만할래」 같은 분명한 종료 문구인가 — 판단이 없을 때만 쓰는 안전망
     */
    public Decision decideWithoutJudgment(StrategySignals before, boolean firstTurn, boolean stopPhrase) {
        return decide(Reading.none(stopPhrase), before, firstTurn);
    }

    private Decision decide(Reading r, StrategySignals before, boolean firstTurn) {
        boolean resisted = "no_answer_or_topic_change".equals(r.reaction()) || "argue".equals(r.reaction());
        // 답하려 했지만 어려워함 — 저항으로 세지 않는다.
        boolean struggled = "struggle_to_answer".equals(r.reaction());

        // ── 신호 갱신 ──
        String explicitMode = before.explicitMode();
        if ("listen".equals(r.requested()) || "refuse_question".equals(r.reaction())) {
            explicitMode = StrategySignals.LISTEN;
        } else if ("explore".equals(r.requested())) {
            explicitMode = StrategySignals.EXPLORE;
        } else if ("other_view".equals(r.requested())) {
            explicitMode = StrategySignals.OTHER_VIEW;
        }
        int topicDepth = before.topicDepth();
        int topicStall = before.topicStall();
        if (r.depth() > topicDepth) {
            topicDepth = r.depth();
            topicStall = 0;
        } else if (r.depth() > 0 && before.lastAsked()) {
            // 질문을 받고도 깊어지지 않은 턴만 센다.
            topicStall++;
        }
        StrategySignals now = new StrategySignals(
                before.resistanceCount() + (resisted ? 1 : 0),
                resisted ? before.resistanceStreak() + 1 : 0,
                explicitMode,
                before.emotionOut() || r.emotionClear(),
                before.eventOut() || r.eventSpecific(),
                before.topicNumber(),
                before.startDepth() > 0 ? before.startDepth() : r.depth(),
                topicDepth, topicStall, before.wrapUpProposed(), before.lastType(), before.lastFocus(),
                before.turnsSinceQuestion(), before.questionsAsked());

        TurnStrategy strategy = choose(r, now, resisted, struggled, firstTurn);
        QuestionFocus focus = strategy.focus();
        boolean askedNow = strategy.asks() && focus != QuestionFocus.WRAP_UP;
        StrategySignals after = new StrategySignals(now.resistanceCount(), now.resistanceStreak(),
                now.explicitMode(), now.emotionOut(),
                // 사건은 한 소재에서 한 번만 묻는다 — 일부만 들었어도 같은 것을 되묻지 않는다.
                now.eventOut() || focus == QuestionFocus.EVENT,
                now.topicNumber(), now.startDepth(), now.topicDepth(), now.topicStall(),
                now.wrapUpProposed() || focus == QuestionFocus.WRAP_UP,
                strategy.type(), focus,
                askedNow ? 0 : now.turnsSinceQuestion() + 1,
                now.questionsAsked() + (askedNow ? 1 : 0));
        return new Decision(strategy, focus == QuestionFocus.OTHER_TOPIC ? after.nextTopic() : after);
    }

    private TurnStrategy choose(Reading r, StrategySignals s, boolean resisted, boolean struggled,
            boolean firstTurn) {
        // ── 1. 먼저 적용하는 제약 (문서 4-2) ──
        if (r.endRequested()) {
            return new TurnStrategy(StrategyType.CLOSE, QuestionFocus.NONE, false);
        }
        boolean corrected = "correct_interpretation".equals(r.reaction());
        StrategyType quiet = corrected ? StrategyType.CORRECT : StrategyType.REFLECT;
        // 들어 달라고 한 사용자에게는 묻지 않는다. 질문 수 상한을 넘어도 받아주기만 한다(4-7).
        if (StrategySignals.LISTEN.equals(s.explicitMode()) || s.questionsAsked() >= questionCap) {
            return new TurnStrategy(quiet, QuestionFocus.NONE, false, r.brief(), struggled);
        }

        // ── 2. 마무리 제안 (문서 4-7) ── 끊지 않고 묻기만 한다. 같은 소재에서는 한 번만 제안한다.
        if (!firstTurn && !corrected && !struggled && !s.wrapUpProposed() && enough(s)) {
            return new TurnStrategy(quiet, QuestionFocus.WRAP_UP, false);
        }

        // ── 3. 유형 ── 기본은 매 턴 인정 뒤에 질문 하나다. 받아주기만 하고 끝내면 대화가 멈춘다.
        int depth = r.depth() > 0 ? r.depth() : s.topicDepth();
        StrategyType type;
        QuestionFocus focus;
        if (StrategySignals.OTHER_VIEW.equals(s.explicitMode())) {
            type = StrategyType.REFRAME;
            focus = QuestionFocus.EVIDENCE;
        } else if (corrected) {
            // 고친 표현을 받은 뒤 같은 턴에 이어서 묻는다. 방금 고쳐 말한 소재에서 다른 기억으로 넘어가지는 않는다.
            type = StrategyType.CORRECT;
            focus = s.eventOut() ? deeper(depth, s.emotionOut()) : QuestionFocus.EVENT;
        } else if (struggled || s.topicStall() >= TOPIC_STALL_LIMIT) {
            // 답하기 어려워했거나(4-8), 마무리를 제안한 뒤에도 이 소재에서 더 깊어지지 않는다 → 이 소재를 떠난다.
            return paced(leaveTopic(s, struggled, r.brief()), s, resisted);
        } else if (!s.eventOut()) {
            type = StrategyType.EXPLORE;
            focus = QuestionFocus.EVENT;
        } else {
            type = StrategyType.EXPLORE;
            focus = deeper(depth, s.emotionOut());
            // 같은 것을 다시 묻게 된다 — 더 어려운 질문으로 올리지 않고 이 소재를 떠난다. 판단이 없는 턴에는 방금 답이
            // 어땠는지 모르므로 소재를 떠나지 않는다.
            if (r.judged() && wouldRepeat(focus, s)) {
                return paced(leaveTopic(s, false, r.brief()), s, resisted);
            }
        }

        return paced(new TurnStrategy(type, focus, false, r.brief()), s, resisted);
    }

    /**
     * 4. 질문 빈도 — 저항이 나온 턴은 질문을 빼고, 저항이 반복된 사용자에게는 턴을 건너 한 번씩 선택지 형태로 묻는다.
     * 마무리 제안은 깊이를 묻는 질문이 아니므로 그대로 둔다.
     */
    private TurnStrategy paced(TurnStrategy chosen, StrategySignals s, boolean resisted) {
        if (!chosen.asks() || chosen.focus() == QuestionFocus.WRAP_UP) {
            return chosen;
        }
        boolean sparse = s.resistanceCount() >= resistanceThreshold;
        if (resisted || (sparse && s.turnsSinceQuestion() < SPARSE_QUESTION_GAP)) {
            return new TurnStrategy(chosen.type(), QuestionFocus.NONE, false, chosen.brief(), chosen.struggled());
        }
        boolean asChoices = sparse && chosen.focus() != QuestionFocus.OTHER_TOPIC;
        return new TurnStrategy(chosen.type(), chosen.focus(), asChoices, chosen.brief(), chosen.struggled());
    }

    /**
     * 이 소재를 떠난다. 첫 소재면 다른 기억을 묻고, 이미 둘째 소재면 마무리를 제안한다(이 소재에서 이미 제안했으면
     * 더 묻지 않고 받아주기만 한다 — 무엇을 더 이야기할지는 사용자에게 맡긴다).
     */
    private static TurnStrategy leaveTopic(StrategySignals s, boolean struggled, boolean brief) {
        if (s.topicNumber() < MAX_TOPICS) {
            return new TurnStrategy(StrategyType.EXPLORE, QuestionFocus.OTHER_TOPIC, false, brief, struggled);
        }
        QuestionFocus focus = s.wrapUpProposed() ? QuestionFocus.NONE : QuestionFocus.WRAP_UP;
        return new TurnStrategy(StrategyType.REFLECT, focus, false, brief, struggled);
    }

    /**
     * 마무리를 제안할 만큼 충분해졌는가 — ① 감정과 사건이 나왔고 깊이가 시작보다 한 단계 올랐거나 5단계 이상에 닿음
     * ② 저항이 연달아 나옴 ③ 같은 것을 다시 물어야 하는 상황이 됨.
     */
    private static boolean enough(StrategySignals s) {
        boolean deepened = s.emotionOut() && s.eventOut()
                && (s.topicDepth() >= 5 || (s.startDepth() > 0 && s.topicDepth() > s.startDepth()));
        return deepened || s.resistanceStreak() >= RESISTANCE_STREAK_LIMIT
                || s.topicStall() >= TOPIC_STALL_LIMIT;
    }

    /**
     * 5. 질문 깊이 — 지금 단계보다 한 단계 깊게. 단계를 모르면 가장 얕은 곳에서 시작한다.
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

    /**
     * 직전 턴과 같은 초점의 질문을 또 하게 되는가. 감정이 나오기 전의 자기 쪽 질문은 예외다 — 감정이 나올 때까지는
     * 달리 물을 것이 없고, 같은 뜻을 다른 말로 다시 물어도 자연스럽다.
     */
    private static boolean wouldRepeat(QuestionFocus focus, StrategySignals s) {
        return s.lastAsked() && focus == s.lastFocus() && focus != QuestionFocus.SELF;
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
