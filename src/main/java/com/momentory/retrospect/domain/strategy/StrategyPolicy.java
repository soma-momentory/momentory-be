package com.momentory.retrospect.domain.strategy;

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
 * </ul>
 */
public class StrategyPolicy {

    public static final String VERSION = "v8";

    /** 한 대화에서 다루는 이야기 수 — 사건은 둘까지 뽑는다. */
    public static final int MAX_TOPICS = 2;
    /** 받아들이지 않는 답이 이만큼 연달아 나오면 마무리를 제안한다. */
    static final int YIELD_STREAK_LIMIT = 2;
    /** 저항이 반복된 사용자에게는 이만큼 턴을 건너 한 번만 묻는다. */
    static final int SPARSE_QUESTION_GAP = 2;

    /** 바바의 직전 말을 받아들이지 않은 반응 — 종류는 달라도 대처는 같다: 물러선다. */
    private static final Set<String> NOT_ACCEPTED = Set.of("correct_interpretation", "argue", "struggle_to_answer",
            "puzzled_or_objecting", "no_answer_or_topic_change");
    /** 그중 주도성 신호로 세는 저항 — 답하지 않음 · 화제 전환 · 반박. */
    private static final Set<String> RESISTANCE = Set.of("argue", "no_answer_or_topic_change");

    private final double minConfidence;
    private final double closeConfidence;
    private final int resistanceThreshold;
    private final int questionCap;

    /**
     * @param minConfidence       이 값 미만의 신호는 없는 것으로 본다
     * @param closeConfidence     종료 요청은 이 값 이상일 때만 바로 마무리한다(회고는 하루 한 번이라 되돌릴 수 없다)
     * @param resistanceThreshold 저항이 이 횟수부터 질문을 줄이고 선택지 형태로 묻는다
     * @param questionCap         바바의 질문이 이 수에 닿으면 더 묻지 않고 마무리를 제안한다
     */
    public StrategyPolicy(double minConfidence, double closeConfidence, int resistanceThreshold, int questionCap) {
        this.minConfidence = minConfidence;
        this.closeConfidence = closeConfidence;
        this.resistanceThreshold = resistanceThreshold;
        this.questionCap = questionCap;
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
                before.turnsSinceQuestion(), before.questionsAsked());
        return new Decision(choose(endRequested, notAccepted, reaction, now), now);
    }

    private TurnStrategy choose(boolean endRequested, boolean notAccepted, String reaction, StrategySignals s) {
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
                    false, reaction);
        }
        // ── 3. 충분히 물었거나 받아들이지 않는 답이 이어지면 마무리를 제안한다(이야기마다 한 번) ──
        boolean askedEnough = s.questionsAsked() >= questionCap;
        if (askedEnough || s.yieldStreak() >= YIELD_STREAK_LIMIT) {
            StrategyType quiet = notAccepted ? StrategyType.YIELD : StrategyType.REFLECT;
            return new TurnStrategy(quiet, s.wrapUpProposed() ? QuestionFocus.NONE : QuestionFocus.WRAP_UP, false,
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
        return chosen.withFocus(s.wrapUpProposed() ? QuestionFocus.NONE : QuestionFocus.WRAP_UP);
    }

    /**
     * 사용자가 새 이야기를 꺼냈다(문장 생성 모델이 알린다) — 이야기 번호를 올리고, 새 이야기에서는 아직 마무리를
     * 제안한 적이 없는 것으로 둔다. 이야기는 {@link #MAX_TOPICS} 까지만 센다.
     */
    public StrategySignals movedToNewTopic(StrategySignals now) {
        if (now.topicNumber() >= MAX_TOPICS) {
            return now;
        }
        return new StrategySignals(now.resistanceCount(), now.yieldStreak(), now.explicitMode(),
                now.topicNumber() + 1, false, now.lastType(), now.turnsSinceQuestion(), now.questionsAsked());
    }

    /** 턴을 마친 뒤의 신호 — 실제로 나간 방침을 반영한다. */
    public StrategySignals settle(StrategySignals now, TurnStrategy done) {
        boolean asked = done.focus() == QuestionFocus.OPEN || done.focus() == QuestionFocus.OTHER_TOPIC;
        return new StrategySignals(now.resistanceCount(), now.yieldStreak(), now.explicitMode(), now.topicNumber(),
                now.wrapUpProposed() || done.focus() == QuestionFocus.WRAP_UP, done.type(),
                asked ? 0 : now.turnsSinceQuestion() + 1, now.questionsAsked() + (asked ? 1 : 0));
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
