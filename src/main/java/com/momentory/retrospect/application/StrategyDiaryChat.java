package com.momentory.retrospect.application;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.momentory.retrospect.application.metering.LlmUsageLogger;
import com.momentory.retrospect.domain.Message;
import com.momentory.retrospect.domain.Phase;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.StrategyReply;
import com.momentory.retrospect.domain.assistant.StrategyReplyWriter;
import com.momentory.retrospect.domain.assistant.TurnJudge;
import com.momentory.retrospect.domain.judgment.TurnJudgment;
import com.momentory.retrospect.domain.judgment.TurnJudgmentLog;
import com.momentory.retrospect.domain.safety.SafetyLevel;
import com.momentory.retrospect.domain.strategy.QuestionFocus;
import com.momentory.retrospect.domain.strategy.StrategyPolicy;
import com.momentory.retrospect.domain.strategy.StrategySignals;
import com.momentory.retrospect.domain.strategy.StrategyType;
import com.momentory.retrospect.domain.strategy.TurnStrategy;
import com.momentory.retrospect.infrastructure.ai.LlmRole;

/**
 * 전략 대화 — 일기 작성 채팅의 한 턴을 세 역할로 나눠 처리한다.
 *
 * <ol>
 * <li><b>판단 모델</b>이 방금 답변에서 신호를 읽는다(끝내려 함 · 받아들이지 않음 · 들어 달라 함 …).
 * <li><b>규칙</b>({@link StrategyPolicy})이 굵은 방침만 정한다 — 끝낼지, 물러설지, 질문을 할지.
 * <li><b>문장 생성 모델</b>이 대화 전체를 보고 그 방침 안에서 말을 쓴다. 무엇을 물을지, 새 이야기인지, 더 물을 것이
 *     남았는지는 이 모델이 정한다.
 * </ol>
 *
 * <p>모델이 「더 물을 것이 없다」고 하면 규칙이 다른 기억을 묻거나 마무리를 제안한다. 얼마나 이야기할지는 턴 수가 아니라
 * 이 흐름이 정하고, 끝없이 이어지지 않게 하는 안전 상한({@code maxTurns})만 둔다. 옛 엔진의 고정 순서 · 5턴 상한 ·
 * 단답 체크인은 여기에 없다.
 *
 * <p>여기서는 한 턴의 결과({@link Outcome})까지만 만든다. 단계 전환(일기 생성 · 마무리 · 안전 안내)은
 * {@link RetrospectEngine} 이 맡는다. 판단 모델이 실패한 턴은 지금까지의 신호로 방침을 고르고, 문장 생성 모델이 실패한
 * 턴은 짧은 고정 문구로 간다.
 */
@Component
public class StrategyDiaryChat {

    /** 문장 생성 모델이 받아주는 말을 쓰지 못했을 때의 짧은 말. */
    static final String PLAIN_ACK = "네, 듣고 있어요.";
    /** 문장 생성 모델이 실패한 턴에 질문이 필요하면 쓰는 말 — 무엇을 물을지 알 수 없으므로 이야기를 열어 둔다. */
    /** 받아줄 말이 따로 없을 때 쓰는 짧은 말들 — 바로 앞에서 한 말과 같은 것은 고르지 않는다. */
    static final List<String> PLAIN_ACKS = List.of(PLAIN_ACK, "네.", "그랬군요.");
    static final String PLAIN_QUESTION = "조금 더 들려주실래요?";
    static final String WRAP_UP_QUESTION = "여기까지 일기로 정리해볼까요?";
    /** 들어 달라고만 하고 받아줄 다른 말이 없을 때의 짧은 말 — 정해 둔 약속 문구(「묻지 않고 들을게요」)는 붙이지 않는다. */
    static final String GO_ON = "편하게 이야기해 주세요.";

    enum Kind {
        /** 바바의 말을 그대로 내보낸다. */
        REPLY,
        /** 받아주는 말 뒤에 마무리를 제안한다 — 정리할지 더 이야기할지 고르게 한다. */
        PROPOSE_WRAP_UP,
        /** 사용자가 끝내려 한다 — 질문 없이 바로 마무리한다. */
        CLOSE,
        /** 안전 상한에 닿았다 — 받아주는 말 뒤에 일기를 정리한다. */
        FINISH
    }

    /**
     * @param text        REPLY 는 바바의 말 전체, PROPOSE_WRAP_UP · FINISH 는 받아주는 말(없으면 빈 문자열), CLOSE 는 null
     * @param safetyLevel 문장 생성 모델이 방금 답변에 매긴 안전 등급 — 엔진이 누적한다
     */
    record Outcome(Kind kind, String text, SafetyLevel safetyLevel, List<String> safetyFlags) {
    }

    private final TurnJudge turnJudge;
    private final StrategyPolicy policy;
    private final StrategyReplyWriter writer;
    private final LlmUsageLogger usage;
    private final int maxTurns;

    public StrategyDiaryChat(TurnJudge turnJudge, StrategyPolicy policy, StrategyReplyWriter writer,
            LlmUsageLogger usage, @Value("${momentory.strategy.max-turns:12}") int maxTurns) {
        this.turnJudge = turnJudge;
        this.policy = policy;
        this.writer = writer;
        this.usage = usage;
        this.maxTurns = maxTurns;
    }

    /**
     * @param userMessage 엔진이 방금 대화 기록에 넣은 사용자 발화
     * @param noReaction  바바의 직전 말이 반응을 볼 만한 것이 아니었는가(첫 질문이거나, 무엇을 말할지 사용자에게 맡긴 뒤)
     * @param stopPhrase  방금 답이 분명한 종료 문구인가 — 판단 모델이 실패했을 때만 쓰는 안전망
     */
    Outcome turn(RetrospectState state, Message userMessage, String content, boolean noReaction,
            boolean stopPhrase) {
        // 바바가 바로 앞에서 다른 기억을 물었다 — 그 뒤에 앞 이야기를 이어 말하는 것은 질문을 피한 것이 아니라 그 이야기를
        // 더 하고 싶은 것이다. 무엇을 말할지 사용자에게 맡긴 뒤처럼 반응을 보지 않고, 문장 생성 모델에도 알린다.
        // 「없어」 같은 분명한 거절은 엔진이 먼저 받아 여기까지 오지 않는다.
        boolean afterOtherMemory = justAskedForAnotherMemory(state, userMessage);
        boolean ignoreReaction = noReaction || afterOtherMemory;
        Optional<TurnJudgment> judgment = turnJudge.judge(state, content);
        state.logJudgment(TurnJudgmentLog.of(userMessage.id(), state.diaryTurn() + 1, judgment.orElse(null)));
        StrategyPolicy.Decision decision = judgment
                .map(j -> policy.decide(j, state.strategySignals(), ignoreReaction))
                .orElseGet(() -> policy.decideWithoutJudgment(state.strategySignals(), stopPhrase));
        // 이 이야기에서 기분을 이미 들었는지(방금 답변 포함)를 문장 생성 모델에 알린다 — 들은 기분을 다시 묻지 않게 한다.
        boolean feelingKnown = policy.observeAnswer(decision.signals(), judgment.orElse(null))
                .questionsAfterFeeling() != null;
        TurnStrategy strategy = decision.strategy().withFeelingKnown(feelingKnown).withAfterOtherMemory(afterOtherMemory);
        if (strategy.type() == StrategyType.CLOSE) {
            state.strategySignals(policy.settle(decision.signals(), strategy));
            state.logStrategy(strategy, StrategyPolicy.VERSION, true);
            state.logAskedFeeling(false);
            state.logQuestionSent(false);
            return new Outcome(Kind.CLOSE, null, SafetyLevel.NONE, List.of());
        }

        Optional<StrategyReply> written = writer.write(state, content, strategy);
        state.bumpDiaryTurn();

        // 들어 달라고(공감해 달라고) 한 턴 — 정해 둔 약속 문구를 앞에 붙이지 않고 모델이 쓴 말을 그대로 쓴다. 받아줄 말이
        // 따로 없으면 편하게 이야기하라고만 한다.
        boolean willListen = strategy.askedToBeHeard();
        String ack = willListen ? GO_ON : PLAIN_ACK;
        String question = null;
        boolean newTopic = false;
        boolean asksFeeling = false;
        boolean repeatsEarlierQuestion = false;
        boolean askedForSide = false;
        SafetyLevel safetyLevel = SafetyLevel.NONE;
        List<String> safetyFlags = List.of();
        if (written.isPresent()) {
            StrategyReply reply = written.get();
            safetyLevel = reply.safetyLevelOrNone();
            safetyFlags = reply.safetyFlags();
            newTopic = reply.newTopic() && state.event() != null;
            // 일의 이름은 일기 대체문에만 쓴다.
            if (reply.topic() != null && !reply.topic().isBlank() && (state.event() == null || reply.newTopic())) {
                state.event(reply.topic());
            }
            String said = withoutVocative(state, RetrospectEngine.empathyStatements(reply.ack()));
            if (willListen) {
                ack = said.isBlank() ? GO_ON : said;
            } else {
                ack = said.isBlank() ? PLAIN_ACK : said;
            }
            question = reply.nothingMoreToAsk() ? null : firstQuestion(withoutVocative(state, reply.question()));
            asksFeeling = reply.questionAsksFeeling();
            repeatsEarlierQuestion = reply.questionRepeatsEarlier();
            askedForSide = reply.userAskedForSide();
        } else {
            state.logGenerationFailed();
            usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(), Phase.DIARY_CHAT.key());
            question = PLAIN_QUESTION;
        }

        ack = notRepeated(state, ack);
        // 새 이야기로 넘어갔는지를 먼저 반영한 뒤에, 더 물을 것이 없을 때 어디로 갈지를 정한다.
        // 새 이야기를 꺼냈으면 앞 이야기를 다 듣지 못했어도 그 이야기를 따라간다. 그것은 받아들이지 않은 것이 아니다.
        StrategySignals signals = newTopic ? policy.movedToNewTopic(decision.signals(), strategy.reaction())
                : decision.signals();
        TurnStrategy done = newTopic && policy.gotNoAnswer(strategy) ? strategy.withoutReaction() : strategy;
        signals = policy.observeAnswer(signals, judgment.orElse(null));
        // 모델이 쓴 질문이 앞에서 바바가 이미 한 질문과 같은 것을 묻는다면 버린다 — 같은 내용을 다시 묻지 않는다.
        if (repeatsEarlierQuestion) {
            question = null;
        }
        if (askedForSide && strategy.focus() == QuestionFocus.OPEN) {
            // 「내가 이상해?」처럼 자기 편을 들어 주길 바랐다 — 이 턴은 그 물음에 사용자 편에서 답하는 턴이다. 질문을 붙이지
            // 않고, 답을 피했다고 보아 마무리를 제안하거나 다른 기억으로 넘어가지도 않는다. 다음 턴부터는 평소대로 묻는다.
            question = null;
            done = done.withFocus(QuestionFocus.NONE);
        } else if (!newTopic && strategy.focus() == QuestionFocus.OPEN && policy.gotNoAnswer(strategy)) {
            // 답하기 어려워했거나 질문과 맞지 않는 말을 했다 — 더 묻지 않고 여기서 정리할지를 묻는다.
            done = policy.whenNoAnswer(strategy, signals);
        } else if (strategy.focus() == QuestionFocus.OPEN && question == null) {
            done = policy.whenNothingMoreToAsk(done, signals, afterOtherMemory);
        }
        // 안전 상한 — 방침이 무엇이든 여기서 정리한다. 이 턴의 방침은 '적용 안 됨'으로 남는다.
        boolean capped = state.diaryTurn() >= maxTurns;
        state.strategySignals(policy.settle(signals, done));
        state.logStrategy(done, StrategyPolicy.VERSION, !capped);
        // 열린 질문이 실제로 나간 턴만 센다 — 다른 기억 질문 · 마무리 제안 · 상한으로 끝난 턴은 탐색 질문도, 기분을 물은 것도
        // 아니다. 문장 생성이 실패해 고정 열린 질문으로 대신한 턴도 질문은 나갔다(생성 실패 표시로 가른다).
        boolean questionSent = !capped && done.focus() == QuestionFocus.OPEN && question != null;
        state.logQuestionSent(questionSent);
        state.logAskedFeeling(questionSent && asksFeeling);
        // 새 이야기였는지는 문장 생성 모델이 판단한다 — 생성이 실패한 턴은 알 수 없으므로 비워 둔다.
        if (written.isPresent()) {
            state.logNewTopic(newTopic);
        }
        if (capped) {
            return new Outcome(Kind.FINISH, ack, safetyLevel, safetyFlags);
        }
        return switch (done.focus()) {
            case WRAP_UP -> new Outcome(Kind.PROPOSE_WRAP_UP, ack, safetyLevel, safetyFlags);
            // 다른 기억을 묻는 문장은 고정이다 — 이 문장 뒤의 거절을 「다른 이야기 없음」으로 알아본다.
            case OTHER_TOPIC -> new Outcome(Kind.REPLY, ack + "\n" + RetrospectEngine.OTHER_MEMORY_QUESTION,
                    safetyLevel, safetyFlags);
            case OPEN -> new Outcome(Kind.REPLY, ack + "\n" + question, safetyLevel, safetyFlags);
            default -> new Outcome(Kind.REPLY, ack, safetyLevel, safetyFlags);
        };
    }

    /**
     * 바바가 바로 앞에서 한 말과 같은 받아주는 말이면 다른 짧은 말로 바꾼다 — 같은 문장을 연달아 하지 않는다.
     * 뜻이 같은 다른 문장까지는 여기서 가려내지 못한다(문장 생성 지시가 맡는다).
     */
    static String notRepeated(RetrospectState state, String ack) {
        String last = null;
        for (var m : state.messages()) {
            if (m.isAssistant()) {
                last = m.content();
            }
        }
        if (last == null || !last.contains(ack)) {
            return ack;
        }
        String said = last;
        return PLAIN_ACKS.stream().filter(a -> !said.contains(a)).findFirst().orElse(ack);
    }

    /** 방금 사용자 발화 바로 앞의 바바의 말이 다른 기억을 묻는 질문이었는가. */
    private static boolean justAskedForAnotherMemory(RetrospectState state, Message userMessage) {
        List<Message> messages = state.messages();
        for (int i = messages.indexOf(userMessage) - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.isAssistant()) {
                return m.content() != null && m.content().endsWith(RetrospectEngine.OTHER_MEMORY_QUESTION);
            }
        }
        return false;
    }

    /**
     * 문장 맨 앞의 호칭("테스터님, ")을 뗀다 — 모델이 매 응답을 호칭으로 시작하고 질문에도 다시 붙였다.
     * 문장 가운데에 자연스럽게 들어간 호칭은 그대로 둔다.
     */
    static String withoutVocative(RetrospectState state, String text) {
        if (text == null || state.nickname() == null || state.nickname().isBlank()) {
            return text;
        }
        return text.strip().replaceFirst(
                "^" + java.util.regex.Pattern.quote(state.nickname().strip()) + "\\s*님[,，]?\\s*", "");
    }

    /** 모델이 쓴 글에서 질문 한 문장을 고른다. 질문이 없으면 null. */
    private static String firstQuestion(String candidate) {
        if (candidate == null) {
            return null;
        }
        for (String part : candidate.strip().split("(?<=[.!?。！？])\\s*|\\R")) {
            String sentence = part.strip();
            if (sentence.endsWith("?") || sentence.endsWith("？")) {
                return sentence;
            }
        }
        return null;
    }
}
