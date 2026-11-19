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
 * <li>v10: v9 가운데 둘을 되돌렸다 — 마음을 들은 뒤의 질문을 서버가 세어 멈추던 것(답이 깊어질 때만, 상한 3)과
 *     마무리 제안을 다시 내던 것. 한 걸음씩 더 안쪽으로 묻는 것은 v8 처럼 문장 생성 모델에 맡기고, 막을 것은
 *     「같은 내용을 다시 묻는 것」 하나로 좁혔다: 모델이 자기 질문이 앞에서 한 질문과 같은 것을 묻는지 함께 알리고,
 *     그렇다면 그 질문을 버린다. v9 의 나머지(답을 얻지 못하면 마무리 제안 · 새 이야기는 바로 따라감 · 들어 달라는
 *     요청에 먼저 답함 · 같은 문장의 받아주는 말을 잇달아 쓰지 않음)는 그대로다.
 * <li>v11: 규칙은 그대로이고 두 흐름을 고쳤다(팀원 대화 라벨링에서 드러남). 「내가 이상해?」처럼 자기 편을 들어 주길 바라는
 *     물음에는 바바가 질문으로 되돌리지 않고 사용자 편에서 답한다 — 문장 생성 모델이 그런 물음인지 알리면 서버가 그 턴의
 *     질문을 빼고, 답을 피했다고 보아 마무리를 제안하지도 않는다(상대를 탓하지는 않는다). 다른 기억을 물은 뒤 사용자가 앞
 *     이야기를 이어 말하면, 질문을 피한 것으로 보아 마무리를 제안하던 것을 고쳐 그 이야기를 따라간다 — 그 답에서는
 *     「직전 응답에 대한 반응」을 보지 않는다(분명한 거절은 전처럼 엔진이 받아 정리한다). 들어 달라거나 공감해 달라는
 *     턴에 서버가 붙이던 「네, 묻지 않고 들을게요」를 뺐다 — 「좀 더 공감해 줘」에도 같은 약속이 나가 어색했다. 공감을
 *     청했으면 앞 이야기에 공감하고, 받을 말이 없을 때만 「편하게 이야기해 주세요」라고 한다. 공감을 청했거나 편을 들어
 *     달라고 한 턴에는 사용자가 쓰지 않은 말이라도 상황에 맞는 감정을 짚어 줄 수 있다(다른 턴은 사용자가 쓴 감정 말만 쓴다).
 * <li>v12: 이야기 수 제한을 없앴다. 둘째 이야기에서 더 물을 것이 없어지자 다른 기억도 더 못 묻고(이야기 둘까지),
 *     첫 이야기에서 낸 마무리 제안이 새 이야기로 넘어와 다시 제안도 못 해, 바바가 질문 없는 받아주기(「네, 듣고
 *     있어요.」 → 「네.」)만 턴 상한까지 되풀이했다. 이제 (1) 더 물을 것이 없으면 몇 번째 이야기든 다른 기억을 묻는다 —
 *     바로 앞 턴에 이미 물었으면 연달아 묻지 않고 마무리를 제안한다. (2) 새 이야기에서는 마무리 제안을 처음부터 다시
 *     센다 — 「조금 더 이야기할래요」 뒤에 꺼낸 이야기에서도 다시 제안할 수 있다. (3) 마무리를 제안한 뒤 질문 없는 턴이
 *     두 번 이어지면 다시 제안한다(v9 에 있다가 v10 에서 뺀 것). 들어 달라고 한 대화에는 적용하지 않는다.
 * <li>v13: 이번 대화의 명시적 요청이 저장된 성향보다 앞선다. 함께 알아보자거나 조언을 구하면 재해석 방침을 풀고, 함께
 *     알아보자 · 조언 · 다른 관점 요청은 쌓인 저항에 따른 드문 질문도 푼다. 요청한 뒤로는 그 대화 끝까지 성향으로 돌아가지 않는다.
 * <li>v14: 규칙은 그대로이고 문장 생성 지시만 바뀌었다(턴 기록에서 구분하려고 버전을 올린다). 기분 다음 질문을 「자기 자신에
 *     대한 마음」에서 「자기 자신에 대한 생각」으로 바꿨다 — 사용자가 기분과 마음의 차이를 느끼지 못했다. 그 생각을 말하면
 *     더 물을 것이 없다고 끝내던 것을 고쳐, 방금 말한 생각을 한 번 더 묻는다. 기분 질문 기록(asksFeeling)은 기분을 물은 턴만 센다.
 *     어떤 일을 더 얘기하고 싶다고 하면 알겠다고 답한 뒤 그 이야기가 이어지는 동안 묻지 않고 공감만 한다. 앞에서 한 말과
 *     같은 말을 하게 되면 그 말을 되풀이하지 않고 다른 기억을 묻는다. 모델이 지금 몇 번째 질문인지 가늠하지 못해, 기분을 들은
 *     뒤의 질문을 서버가 세어 이번에 물을 것을 알리고 두 번(자기 자신에 대한 생각 → 그 생각)을 물으면 그 이야기의 질문을 마친다.
 *     위기 등급은 방금 답변 하나와 사용자 본인의 위험만 보고 매기게 했다(남의 죽음 이야기 · 괜찮다는 해명에 안내가 거듭 나갔다).
 *     뉴스 · 남의 일처럼 지켜본 일이면 기분 다음에 자기 자신이 아니라 그 일이나 그 사람에 대한 생각을 묻는다.
 *     남의 일에 자기 처지를 겹쳐 말하면 그 사람이 아니라 사용자 자신의 처지를 묻는다.
 *     이야기를 시작할 때 무슨 일인지 막연하면 기분보다 먼저 그 일을 구체적으로 한 번 묻는다(이야기마다 서버가 센다).
 * </ul>
 */
public class StrategyPolicy {

    public static final String VERSION = "v14";

    /** 받아들이지 않는 답이 이만큼 연달아 나오면 마무리를 제안한다. */
    static final int YIELD_STREAK_LIMIT = 2;
    /** 저항이 반복된 사용자에게는 이만큼 턴을 건너 한 번만 묻는다. */
    static final int SPARSE_QUESTION_GAP = 2;
    /** 마무리를 제안한 뒤 질문 없는 턴이 이만큼 이어지면 다시 제안한다. */
    static final int REPROPOSE_AFTER_QUIET_TURNS = 2;
    /** 한 이야기에서 기분을 들은 뒤에 묻는 질문 수 — 자기 자신에 대한 생각, 그리고 그 생각을 한 번 더. */
    static final int QUESTIONS_AFTER_FEELING = 2;
    /** 한 이야기에서 무슨 일이었는지 구체적으로 묻는 질문 수 — 막연하게 말했을 때 한 번만 묻고 기분으로 넘어간다. */
    static final int DETAIL_QUESTIONS = 1;

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
            // 함께 알아보자거나 조언을 구하면 「듣기만」도, 저장된 성향의 재해석도 풀린다.
            explicitMode = null;
        }
        // 직접 요청한 방식을 드문 질문이 가로막지 않게 한다 — 저장된 성향으로 미리 채운 저항도 여기서 풀린다.
        boolean askedForAWay = "explore".equals(requested) || "advice".equals(requested)
                || "other_view".equals(requested);
        int resistance = before.resistanceCount() + (reaction != null && RESISTANCE.contains(reaction) ? 1 : 0);
        // 직접 더 알아보자고 했으면 이 이야기의 질문을 다 물었어도 한 번 더 물을 수 있다.
        Integer afterFeeling = askedForAWay && before.questionsAfterFeeling() != null
                ? Integer.valueOf(Math.min(before.questionsAfterFeeling(), QUESTIONS_AFTER_FEELING - 1))
                : before.questionsAfterFeeling();
        StrategySignals now = new StrategySignals(askedForAWay ? 0 : resistance,
                notAccepted ? before.yieldStreak() + 1 : 0,
                explicitMode, before.topicNumber(), before.wrapUpProposed(), before.lastType(),
                before.turnsSinceQuestion(), before.questionsAsked(), afterFeeling,
                before.deepestStage(), before.detailQuestions());
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
        // ── 2. 들어 달라고 했거나 어떤 일을 더 얘기하고 싶다고 했으면 묻지 않는다 ──
        if (StrategySignals.LISTEN.equals(s.explicitMode()) || StrategySignals.HEAR_OUT.equals(s.explicitMode())) {
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
     * 문장 생성 모델이 「더 물을 것이 없다」고 했을 때 이번 턴을 어떻게 마칠지 — 몇 번째 이야기든 다른 기억을 묻는다.
     * 바로 앞 턴에 다른 기억을 물었는데 사용자가 앞 이야기를 이어 말했고 그마저 더 물을 것이 없으면, 같은 질문을 연달아 하지
     * 않고 마무리를 제안한다. 갈 곳이 없다고 받아주기만 하지 않는다.
     *
     * @param justAskedForAnotherMemory 바바의 바로 앞 말이 다른 기억을 묻는 질문이었는가
     */
    public TurnStrategy whenNothingMoreToAsk(TurnStrategy chosen, StrategySignals s, boolean justAskedForAnotherMemory) {
        return chosen.withFocus(justAskedForAnotherMemory ? QuestionFocus.WRAP_UP : QuestionFocus.OTHER_TOPIC);
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
     * 마무리도 제안한 적이 없는 것으로 둔다. 몇 번째 이야기든 따라간다. 바로 앞에서 낸 마무리 제안을 사용자가 마다하고
     * 꺼낸 이야기여도 그렇다 — 그 이야기에서 더 물을 것이 없어지면 다시 제안할 수 있어야 한다.
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
        // 더 얘기하고 싶다던 것은 앞 이야기다 — 새 이야기에서는 다시 묻는다.
        String mode = StrategySignals.HEAR_OUT.equals(now.explicitMode()) ? null : now.explicitMode();
        // 마음을 들은 뒤의 질문 수는 새 이야기에서 다시 센다(8개짜리 생성자가 비워 둔다).
        return new StrategySignals(wasTopicChange ? Math.max(0, now.resistanceCount() - 1) : now.resistanceCount(),
                wasTopicChange ? 0 : now.yieldStreak(), mode,
                now.topicNumber() + 1, false, now.lastType(), now.turnsSinceQuestion(),
                now.questionsAsked());
    }

    /**
     * 사용자가 어떤 일을 더 얘기하고 싶다고 했다(문장 생성 모델이 알린다) — 그 이야기가 이어지는 동안 묻지 않는다.
     * 대화 내내 묻지 말라고 한 요청은 더 넓은 요청이므로 그대로 둔다.
     */
    public StrategySignals hearingOut(StrategySignals now) {
        return StrategySignals.LISTEN.equals(now.explicitMode()) ? now
                : now.withExplicitMode(StrategySignals.HEAR_OUT);
    }

    /** 이 이야기에서 기분을 들은 뒤에 물을 질문을 다 물었는가. */
    public boolean askedEnoughAfterFeeling(StrategySignals s) {
        return s.questionsAfterFeeling() != null && s.questionsAfterFeeling() >= QUESTIONS_AFTER_FEELING;
    }

    /** 이 이야기에서 무슨 일이었는지 구체적으로 묻는 질문을 다 물었는가 — 그 뒤로는 사실을 더 캐묻지 않는다. */
    public boolean askedEnoughDetail(StrategySignals s) {
        return s.detailQuestions() >= DETAIL_QUESTIONS;
    }

    /** 턴을 마친 뒤의 신호 — 실제로 나간 방침을 반영한다. */
    public StrategySignals settle(StrategySignals now, TurnStrategy done) {
        return settle(now, done, false);
    }

    /**
     * @param detailQuestion 이번 턴에 나간 질문이 무슨 일이었는지 구체적으로 묻는 질문인가 — 이야기마다 세고, 기분을 들은 뒤의
     *                       질문(자기 자신에 대한 생각 → 그 생각)으로는 세지 않는다
     */
    public StrategySignals settle(StrategySignals now, TurnStrategy done, boolean detailQuestion) {
        boolean asked = done.focus() == QuestionFocus.OPEN || done.focus() == QuestionFocus.OTHER_TOPIC;
        // 마무리 제안도 사용자에게 답을 구한 턴이다 — 「질문 없는 턴」은 그 뒤부터 다시 센다.
        boolean sought = asked || done.focus() == QuestionFocus.WRAP_UP;
        // 마음을 들은 뒤에 한 질문을 센다 — 다른 기억을 묻는 질문은 이 이야기의 질문이 아니다.
        Integer afterFeeling = now.questionsAfterFeeling();
        boolean detailAsked = detailQuestion && done.focus() == QuestionFocus.OPEN;
        if (afterFeeling != null && done.focus() == QuestionFocus.OPEN && !detailAsked) {
            afterFeeling++;
        }
        return new StrategySignals(now.resistanceCount(), now.yieldStreak(), now.explicitMode(), now.topicNumber(),
                now.wrapUpProposed() || done.focus() == QuestionFocus.WRAP_UP, done.type(),
                sought ? 0 : now.turnsSinceQuestion() + 1, now.questionsAsked() + (asked ? 1 : 0), afterFeeling,
                now.deepestStage(), now.detailQuestions() + (detailAsked ? 1 : 0));
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

    private static final Map<String, Integer> DEPTH_RANK = Map.of(
            "stage_1_2", 1, "stage_3", 2, "stage_4", 3, "stage_5_plus", 4);

    /**
     * 방금 답변에서 본 것을 신호에 남긴다 — 이 이야기에서 그때의 마음을 들었는지와, 가장 깊었던 체험 깊이.
     * 마음을 들었는지는 들은 기분을 다시 묻지 않게 하고 그 뒤의 질문을 세는 데 쓰고, 깊이는 기록으로만 남는다.
     * 확신이 기준에 못 미치는 판단은 없는 것으로 본다.
     *
     * @param judgment 방금 답변의 판단. 없으면 null
     */
    public StrategySignals observeAnswer(StrategySignals s, TurnJudgment judgment) {
        if (judgment == null) {
            return s;
        }
        String depth = signal(judgment, JudgmentItem.EXPERIENCING_DEPTH);
        Integer rank = depth == null ? null : DEPTH_RANK.get(depth);
        boolean deeper = rank != null && (s.deepestStage() == null || rank > s.deepestStage());
        boolean heardNow = "clear".equals(signal(judgment, JudgmentItem.EMOTION_EXPRESSION));
        return s.withFollowUp(s.questionsAfterFeeling() == null && heardNow ? Integer.valueOf(0)
                : s.questionsAfterFeeling(), deeper ? rank : s.deepestStage());
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
