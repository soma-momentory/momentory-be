package com.momentory.retrospect.domain.strategy;

import java.util.Map;
import java.util.Set;

import com.momentory.retrospect.domain.judgment.ItemJudgment;
import com.momentory.retrospect.domain.judgment.JudgmentItem;
import com.momentory.retrospect.domain.judgment.TurnJudgment;

/**
 * 턴 판단과 이번 대화의 신호로 이번 턴의 <b>굵은 방침</b>을 고른다 — 순수 규칙이고 모델을 부르지 않는다.
 *
 * <p>규칙이 정하는 것은 넷뿐이다: 끝낼지, 물러설지, 질문을 할지, 언제 마무리를 제안할지. <b>무엇을 물을지는 정하지 않는다</b> —
 * 이미 답한 것인지, 새 이야기인지, 더 물을 것이 남았는지는 대화 전체를 보는 문장 생성 모델만 알 수 있다. 감정 · 사건 · 체험 깊이
 * 판단은 방침을 고르는 데 쓰지 않는 측정값이다(턴별 기록에 남아 결과 지표가 된다).
 *
 * <p>방침을 바꾸는 신호(끝내려 함 · 받아들이지 않음 · 들어 달라 함 · 다른 관점을 원함)만 판단에서 읽고, 확신이 기준에
 * 못 미치면 없는 것으로 본다. 규칙을 고치면 {@link #VERSION} 을 올린다.
 *
 * <p>버전 이력:
 * <ul>
 * <li>v1~v6: 규칙이 체험 깊이 판단으로 질문의 초점을 골랐다. 대화마다 상황별 규칙을 더했고, 규칙끼리 부딪혀 깨졌다 —
 *     깊이 판단의 확신이 낮아 엉뚱한 초점이 골라지고, 사용자가 꺼낸 새 이야기를 알아보지 못해 앞 이야기의 감정을 섞었다.
 * <li>v7: 규칙은 굵은 방침만 정한다. 받아들이지 않는 반응(고쳐 말함 · 반박 · 답하기 어려워함 · 의아해함 · 다른 얘기로 넘어감)은
 *     종류를 가리지 않고 물러선다. 질문의 내용과 「더 물을 것이 없음」은 문장 생성 모델이 정하고, 그 결과로 다른 기억을
 *     묻거나 마무리를 제안한다({@link #settle}).
 * <li>v8: 규칙은 그대로이고 문장 생성 지시만 바뀌었다(턴 기록에서 구분하려고 버전을 올린다). v7 대화에서 질문이 모두
 *     사실 쪽으로 가 감정이 하나도 나오지 않았다 — 질문은 그 일을 겪은 마음을 향하고, 무슨 일인지 알게 됐으면 사실을
 *     더 캐묻지 않으며, 마음을 한 번도 묻지 않은 이야기는 「더 물을 것이 없음」으로 끝내지 않는다. 같은 질문은 두 번 하지
 *     않는다 — 답을 듣지 못한 질문은 되풀이하지 않고, 다른 기억을 이미 물었으면 다시 묻는 대신 마무리를 제안한다.
 * <li>v9: 마무리를 제안한 뒤 질문 없는 턴이 두 번 이어지면 다시 제안한다. v8 까지는 이야기마다 한 번만 제안해서, 더 물을 것이
 *     없는 상태에서 "맞아", "그래" 같은 답이 이어지면 바바가 같은 공감을 말만 바꿔 되풀이했고 끝낼 길이 보이지 않았다.
 *     들어 달라고 한 대화에는 적용하지 않는다 — 그때의 질문 없는 턴은 사용자가 원한 것이다.
 *     그때의 마음을 들은 뒤에는 한 번 더 묻고, 그 뒤로는 답이 앞보다 깊어졌을 때만 이어 묻는다(상한은 설정값).
 *     v8 은 언제 멈출지를 문장 생성 모델에 맡겼는데 잘 멈추지 않아, 감정을 답한 사용자에게 질문이 이어졌다.
 *     체험 깊이 판단을 다시 규칙에 쓰지만 무엇을 물을지가 아니라 멈출지에만 쓰고, 확신이 낮으면 깊어지지 않은 것으로
 *     본다 — 판단이 틀려도 더 묻는 쪽이 아니라 멈추는 쪽으로 틀린다. 다른 관점을 원한 사용자의 재해석 질문은 세지 않는다.
 *     답하기 어려워하거나 질문과 맞지 않는 말을 하면 다른 기억을 묻지 않고 여기서 정리할지를 묻는다(v4~v8 은 다른
 *     기억으로 넘어갔다). 사용자가 새 이야기를 꺼내면 앞 이야기를 다 듣지 못했어도 곧바로 그 이야기를 따라가고, 그것을
 *     저항으로 세지 않는다. 이야기 수에 따라 새 이야기를 막지 않는다(사건은 여전히 둘까지 뽑는다).
 *     들어 달라고 한 바로 그 턴에는 서버가 「묻지 않고 듣겠다」고 먼저 답한다 — 문장 생성 모델에 맡기면 요청을 해석하거나
 *     되풀이해서("그저 털어놓고 싶었던 거군요") 사용자가 같은 요청을 다시 해야 했다.
 * </ul>
 */
public class StrategyPolicy {

    public static final String VERSION = "v9";

    /** 한 대화에서 다루는 이야기 수 — 사건은 둘까지 뽑는다. */
    public static final int MAX_TOPICS = 2;
    /** 받아들이지 않는 답이 이만큼 연달아 나오면 마무리를 제안한다. */
    static final int YIELD_STREAK_LIMIT = 2;
    /** 저항이 반복된 사용자에게는 이만큼 턴을 건너 한 번만 묻는다. */
    static final int SPARSE_QUESTION_GAP = 2;
    /** 마무리를 제안한 뒤 질문 없는 턴이 이만큼 이어지면 다시 제안한다. */
    static final int REPROPOSE_AFTER_QUIET_TURNS = 2;

    /** 바바의 직전 말을 받아들이지 않은 반응 — 종류는 달라도 대처는 같다: 물러선다. */
    private static final Set<String> NOT_ACCEPTED = Set.of("correct_interpretation", "argue", "struggle_to_answer",
            "puzzled_or_objecting", "no_answer_or_topic_change");
    /** 그중 주도성 신호로 세는 저항 — 답하지 않음 · 화제 전환 · 반박. */
    private static final String TOPIC_CHANGE = "no_answer_or_topic_change";
    /** 질문에 답을 얻지 못한 반응 — 답하기 어려워했거나 질문과 맞지 않는 말을 했다. 더 묻지 않고 여기서 정리할지를 묻는다. */
    private static final Set<String> NO_ANSWER = Set.of("struggle_to_answer", TOPIC_CHANGE);
    private static final Set<String> RESISTANCE = Set.of("argue", "no_answer_or_topic_change");

    private final double minConfidence;
    private final double closeConfidence;
    private final int resistanceThreshold;
    private final int questionCap;
    private final int followUpLimit;

    /**
     * @param minConfidence       이 값 미만의 신호는 없는 것으로 본다
     * @param closeConfidence     종료 요청은 이 값 이상일 때만 바로 마무리한다(회고는 하루 한 번이라 되돌릴 수 없다)
     * @param resistanceThreshold 저항이 이 횟수부터 질문을 줄이고 선택지 형태로 묻는다
     * @param questionCap         바바의 질문이 이 수에 닿으면 더 묻지 않고 마무리를 제안한다
     * @param followUpLimit       한 이야기에서 그때의 마음을 들은 뒤 더 묻는 횟수의 상한 — 답이 깊어지는 동안에만 쓴다
     */
    public StrategyPolicy(double minConfidence, double closeConfidence, int resistanceThreshold, int questionCap,
            int followUpLimit) {
        this.minConfidence = minConfidence;
        this.closeConfidence = closeConfidence;
        this.resistanceThreshold = resistanceThreshold;
        this.questionCap = questionCap;
        this.followUpLimit = followUpLimit;
    }

    /** 마음을 들은 뒤의 질문을 세 번까지로 두는 기본 규칙. */
    public StrategyPolicy(double minConfidence, double closeConfidence, int resistanceThreshold, int questionCap) {
        this(minConfidence, closeConfidence, resistanceThreshold, questionCap, 3);
    }

    /** 고른 방침과, 이번 턴의 신호까지 반영한 값. 턴이 끝나면 {@link #settle} 로 마무리한다. */
    public record Decision(TurnStrategy strategy, StrategySignals signals) {
    }

    /**
     * @param noReaction 바바의 직전 말이 반응을 볼 만한 것이 아니었는가 — 첫 질문이거나, 「더 나누고 싶은 이야기를 들려주세요」처럼
     *                   무엇을 말할지 사용자에게 맡긴 뒤다. 이때 다른 얘기를 꺼내는 것은 받아들이지 않은 것이 아니다.
     */
    public Decision decide(TurnJudgment judgment, StrategySignals before, boolean noReaction) {
        ItemJudgment end = judgment.of(JudgmentItem.END_INTENT);
        boolean endRequested = "end_request".equals(end.choice()) && end.confidence() >= closeConfidence;
        return decide(endRequested, noReaction ? null : signal(judgment, JudgmentItem.REACTION),
                signal(judgment, JudgmentItem.REQUESTED_MODE), before);
    }

    /**
     * 판단이 없는 턴(판단 모델 실패) — 지금까지의 신호만으로 방침을 고른다.
     *
     * @param stopPhrase 사용자의 답이 「그만할래」 같은 분명한 종료 문구인가 — 판단이 없을 때만 쓰는 안전망
     */
    public Decision decideWithoutJudgment(StrategySignals before, boolean stopPhrase) {
        return decide(stopPhrase, null, null, before);
    }

    private Decision decide(boolean endRequested, String reaction, String requested, StrategySignals before) {
        boolean notAccepted = reaction != null && NOT_ACCEPTED.contains(reaction);
        String explicitMode = before.explicitMode();
        if ("listen".equals(requested) || "refuse_question".equals(reaction)) {
            explicitMode = StrategySignals.LISTEN;
        } else if ("other_view".equals(requested)) {
            explicitMode = StrategySignals.OTHER_VIEW;
        } else if ("explore".equals(requested) || "advice".equals(requested)) {
            // 함께 알아보자거나 조언을 구하면 「듣기만」은 풀린다.
            explicitMode = StrategySignals.LISTEN.equals(explicitMode) ? null : explicitMode;
        }
        StrategySignals now = new StrategySignals(
                before.resistanceCount() + (reaction != null && RESISTANCE.contains(reaction) ? 1 : 0),
                notAccepted ? before.yieldStreak() + 1 : 0,
                explicitMode, before.topicNumber(), before.wrapUpProposed(), before.lastType(),
                before.turnsSinceQuestion(), before.questionsAsked(), before.questionsAfterFeeling(),
                before.deepestStage());
        boolean askedToBeHeard = "listen".equals(requested) || "refuse_question".equals(reaction);
        return new Decision(choose(endRequested, notAccepted, reaction, askedToBeHeard, now), now);
    }

    private TurnStrategy choose(boolean endRequested, boolean notAccepted, String reaction, boolean askedToBeHeard,
            StrategySignals s) {
        // ── 1. 끝내려 하면 마무리한다 ──
        if (endRequested) {
            return new TurnStrategy(StrategyType.CLOSE, QuestionFocus.NONE, false, reaction);
        }
        StrategyType base = notAccepted ? StrategyType.YIELD
                : StrategySignals.OTHER_VIEW.equals(s.explicitMode()) ? StrategyType.REFRAME
                : StrategyType.EXPLORE;
        // ── 2. 들어 달라고 했으면 묻지 않는다 ──
        if (StrategySignals.LISTEN.equals(s.explicitMode())) {
            return new TurnStrategy(notAccepted ? StrategyType.YIELD : StrategyType.REFLECT, QuestionFocus.NONE,
                    false, reaction, askedToBeHeard);
        }
        // ── 3. 충분히 물었거나 받아들이지 않는 답이 이어지면 마무리를 제안한다(이야기마다 한 번) ──
        boolean askedEnough = s.questionsAsked() >= questionCap;
        if (askedEnough || s.yieldStreak() >= YIELD_STREAK_LIMIT) {
            StrategyType quiet = notAccepted ? StrategyType.YIELD : StrategyType.REFLECT;
            return new TurnStrategy(quiet, wrapUpDue(s) ? QuestionFocus.WRAP_UP : QuestionFocus.NONE, false,
                    reaction);
        }
        // ── 4. 질문 빈도 — 저항이 반복된 사용자에게는 턴을 건너 한 번씩, 선택지 형태로 묻는다 ──
        boolean sparse = s.resistanceCount() >= resistanceThreshold;
        if (sparse && s.turnsSinceQuestion() < SPARSE_QUESTION_GAP) {
            return new TurnStrategy(base, QuestionFocus.NONE, false, reaction);
        }
        // ── 5. 그 외에는 인정 뒤에 질문 하나 — 무엇을 물을지는 문장 생성 모델이 정한다 ──
        return new TurnStrategy(base, QuestionFocus.OPEN, sparse, reaction);
    }

    /**
     * 문장 생성 모델이 「더 물을 것이 없다」고 했을 때 이번 턴을 어떻게 마칠지 — 첫 이야기면 다른 기억을 묻고,
     * 이미 둘째 이야기면 마무리를 제안한다(이 이야기에서 이미 제안했으면 받아주기만 한다).
     *
     * @param otherMemoryAsked 이 대화에서 다른 기억을 이미 물었는가 — 같은 질문은 두 번 하지 않고 마무리를 제안한다
     */
    public TurnStrategy whenNothingMoreToAsk(TurnStrategy chosen, StrategySignals s, boolean otherMemoryAsked) {
        if (s.topicNumber() < MAX_TOPICS && !otherMemoryAsked) {
            return chosen.withFocus(QuestionFocus.OTHER_TOPIC);
        }
        return chosen.withFocus(wrapUpDue(s) ? QuestionFocus.WRAP_UP : QuestionFocus.NONE);
    }

    /**
     * 지금 마무리를 제안할 차례인가 — 이 이야기에서 아직 제안하지 않았거나, 제안한 뒤 질문 없는 턴이 이어졌다.
     * 더 물을 것이 없는데 제안도 다시 하지 않으면 바바가 같은 공감만 되풀이하고, 사용자는 끝낼 길을 찾지 못한다.
     */
    private static boolean wrapUpDue(StrategySignals s) {
        return !s.wrapUpProposed() || s.turnsSinceQuestion() >= REPROPOSE_AFTER_QUIET_TURNS;
    }

    /**
     * 사용자가 새 이야기를 꺼냈다(문장 생성 모델이 알린다) — 이야기 번호를 올리고, 새 이야기에서는 마음을 아직 듣지 못했고
     * 마무리도 제안한 적이 없는 것으로 둔다. 몇 번째 이야기든 따라간다(사건은 여전히 {@link #MAX_TOPICS} 까지 뽑는다).
     */
    public StrategySignals movedToNewTopic(StrategySignals now) {
        return movedToNewTopic(now, null);
    }

    /**
     * @param reaction 이 턴에 규칙이 본 반응 — 「다른 얘기로 넘어감」이었다면 그것은 새 이야기를 꺼낸 것이지 저항이 아니므로
     *                 이 턴에 센 저항과 「받아들이지 않음」을 되돌린다
     */
    public StrategySignals movedToNewTopic(StrategySignals now, String reaction) {
        boolean wasTopicChange = TOPIC_CHANGE.equals(reaction);
        // 바로 앞 턴에 마무리를 제안했으면 새 이야기에서도 곧바로 다시 제안하지 않는다 — 같은 제안을 연달아 하지 않는다.
        boolean justProposed = now.wrapUpProposed() && now.turnsSinceQuestion() == 0;
        // 마음을 들은 뒤의 질문 수는 새 이야기에서 다시 센다(8개짜리 생성자가 비워 둔다).
        return new StrategySignals(wasTopicChange ? Math.max(0, now.resistanceCount() - 1) : now.resistanceCount(),
                wasTopicChange ? 0 : now.yieldStreak(), now.explicitMode(),
                now.topicNumber() + 1, justProposed, now.lastType(), now.turnsSinceQuestion(),
                now.questionsAsked());
    }

    /** 턴을 마친 뒤의 신호 — 실제로 나간 방침을 반영한다. */
    public StrategySignals settle(StrategySignals now, TurnStrategy done) {
        boolean asked = done.focus() == QuestionFocus.OPEN || done.focus() == QuestionFocus.OTHER_TOPIC;
        // 마무리 제안도 사용자에게 답을 구한 턴이다 — 「질문 없는 턴」은 그 뒤부터 다시 센다.
        boolean sought = asked || done.focus() == QuestionFocus.WRAP_UP;
        // 마음을 들은 뒤에 한 질문을 센다 — 다른 기억을 묻는 질문은 이 이야기의 질문이 아니다.
        Integer afterFeeling = now.questionsAfterFeeling();
        if (afterFeeling != null && done.focus() == QuestionFocus.OPEN) {
            afterFeeling++;
        }
        return new StrategySignals(now.resistanceCount(), now.yieldStreak(), now.explicitMode(), now.topicNumber(),
                now.wrapUpProposed() || done.focus() == QuestionFocus.WRAP_UP, done.type(),
                sought ? 0 : now.turnsSinceQuestion() + 1, now.questionsAsked() + (asked ? 1 : 0), afterFeeling,
                now.deepestStage());
    }

    /** 한 대화 안에서 저항이 이 횟수부터 질문을 줄인다. */
    public int resistanceThreshold() {
        return resistanceThreshold;
    }

    /**
     * 지난 대화에서 도출한 성향으로 시작하는 대화의 첫 신호. 성향이 없으면 기본값과 같다.
     *
     * <p>스스로 이끌고 싶어 하는 사용자는 처음부터 질문을 드물게, 고를 수 있는 형태로 묻는다 — 첫 답변에는 한 번 묻고
     * 그 뒤로 턴을 건넌다. 다른 관점을 원하는 사용자는 재해석 방침으로 시작한다. 어느 쪽이든 이번 대화에서 나온
     * 명시적 요청이 앞선다.
     */
    public StrategySignals startingSignals(boolean selfLed, boolean wantsOtherView) {
        return new StrategySignals(selfLed ? resistanceThreshold : 0, 0,
                wantsOtherView ? StrategySignals.OTHER_VIEW : null, 1, false, null,
                selfLed ? SPARSE_QUESTION_GAP : 0, 0);
    }

    /** 방금 답변을 반영한 신호와, 이 이야기에서 더 물어도 되는지. */
    public record FollowUp(StrategySignals signals, boolean mayAsk) {
    }

    private static final Map<String, Integer> DEPTH_RANK = Map.of(
            "stage_1_2", 1, "stage_3", 2, "stage_4", 3, "stage_5_plus", 4);

    /**
     * 방금 답변으로 이 이야기를 더 물어도 되는지 정한다 — 한 걸음 더 깊어질 기회는 주되, 이미 답한 사람에게 질문을 잇지 않는다.
     *
     * <ul>
     * <li>그때의 마음을 아직 듣지 못했으면 물어도 된다.
     * <li>마음을 들은 턴에는 한 번 더 물어도 된다(누구에게나 한 번).
     * <li>그 뒤로는 방금 답이 이 이야기에서 가장 깊었던 답보다 더 깊어졌을 때만, 상한에 닿기 전까지 묻는다.
     * </ul>
     *
     * 확신이 기준에 못 미치거나 판단할 수 없는 깊이는 깊어지지 않은 것으로 본다. 판단이 없는 턴(판단 모델 실패)도 같다.
     *
     * @param judgment 방금 답변의 판단. 없으면 null
     */
    public FollowUp afterAnswer(StrategySignals s, TurnJudgment judgment) {
        Integer rank = judgment == null ? null : DEPTH_RANK.get(signalOrEmpty(judgment, JudgmentItem.EXPERIENCING_DEPTH));
        boolean heardNow = judgment != null && "clear".equals(signal(judgment, JudgmentItem.EMOTION_EXPRESSION));
        boolean deeper = rank != null && (s.deepestStage() == null || rank > s.deepestStage());
        Integer deepest = deeper ? rank : s.deepestStage();
        Integer asked = s.questionsAfterFeeling() == null && heardNow ? Integer.valueOf(0) : s.questionsAfterFeeling();
        boolean mayAsk = asked == null || asked == 0 || (deeper && asked < followUpLimit);
        return new FollowUp(s.withFollowUp(asked, deepest), mayAsk);
    }

    private String signalOrEmpty(TurnJudgment judgment, JudgmentItem item) {
        String choice = signal(judgment, item);
        return choice == null ? "" : choice;
    }

    /** 방금 답이 질문에 답을 주지 못했는가 — 답하기 어려워했거나 질문과 맞지 않는 말을 했다. */
    public boolean gotNoAnswer(TurnStrategy strategy) {
        return strategy.reaction() != null && NO_ANSWER.contains(strategy.reaction());
    }

    /**
     * 질문에 답을 얻지 못했을 때 — 다른 기억을 묻거나 바꿔 묻지 않고, 여기서 정리할지를 묻는다. 방금 제안했으면 연달아
     * 제안하지 않고 받아주기만 한다. 사용자가 새 이야기를 꺼낸 경우는 여기로 오지 않는다(그 이야기를 따라간다).
     */
    public TurnStrategy whenNoAnswer(TurnStrategy chosen, StrategySignals s) {
        return chosen.withFocus(wrapUpDue(s) ? QuestionFocus.WRAP_UP : QuestionFocus.NONE);
    }

    /** 주도성 신호로 세는 저항인가 — 답하지 않음 · 화제 전환 · 반박. */
    public static boolean isResistance(String reaction) {
        return reaction != null && RESISTANCE.contains(reaction);
    }

    /** 확신이 기준 이상이고 '판단 불가'가 아닌 선택지만 돌려준다. 아니면 null. */
    private String signal(TurnJudgment judgment, JudgmentItem item) {
        ItemJudgment j = judgment.of(item);
        if (j.confidence() < minConfidence || "undeterminable".equals(j.choice())) {
            return null;
        }
        return j.choice();
    }
}
