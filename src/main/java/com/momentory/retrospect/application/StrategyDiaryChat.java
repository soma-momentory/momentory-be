package com.momentory.retrospect.application;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.momentory.retrospect.application.metering.LlmUsageLogger;
import com.momentory.retrospect.domain.Message;
import com.momentory.retrospect.domain.Phase;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.DiaryChatAssistant;
import com.momentory.retrospect.domain.assistant.DiaryTurn;
import com.momentory.retrospect.domain.assistant.TurnJudge;
import com.momentory.retrospect.domain.judgment.TurnJudgment;
import com.momentory.retrospect.domain.judgment.TurnJudgmentLog;
import com.momentory.retrospect.domain.safety.SafetyLevel;
import com.momentory.retrospect.domain.strategy.QuestionFocus;
import com.momentory.retrospect.domain.strategy.StrategyPolicy;
import com.momentory.retrospect.domain.strategy.StrategyType;
import com.momentory.retrospect.domain.strategy.TurnStrategy;
import com.momentory.retrospect.infrastructure.ai.LlmRole;

/**
 * 전략 대화 — 일기 작성 채팅의 한 턴을 설계 문서의 흐름만으로 처리한다:
 * <b>상태 판단 → 규칙으로 전략 선택 → 전략에 맞춘 문장 생성</b>.
 *
 * <p>옛 엔진의 고정 순서(기분 → 계기 → 감정 확인 → 다른 소재) · 5턴 상한 · 단답 체크인은 여기에 없다. 얼마나 이야기할지는
 * 턴 수가 아니라 전략이 정한다 — 충분해지면 마무리를 제안하고, 질문 수 상한을 넘으면 받아주기만 한다. 끝없이 이어지지 않게
 * 하는 안전 상한({@code maxTurns})만 둔다.
 *
 * <p>여기서는 한 턴의 결과({@link Outcome})까지만 만든다. 단계 전환(일기 생성 · 감정 탐색으로 넘어가기 · 안전 안내)은
 * {@link RetrospectEngine} 이 맡는다. 판단 모델이 실패한 턴은 지금까지의 신호로 기본값을 고르고, 문장 생성 모델이 실패한
 * 턴은 짧은 고정 문구와 초점에 맞는 고정 질문으로 간다 — 어느 쪽도 옛 고정 순서로 돌아가지 않는다.
 */
@Component
public class StrategyDiaryChat {

    /** 문장 생성 모델이 받아주는 말을 쓰지 못했을 때의 짧은 말. */
    static final String PLAIN_ACK = "네, 듣고 있어요.";
    static final String WRAP_UP_QUESTION = "여기까지 일기로 정리해볼까요?";

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
    private final DiaryChatAssistant assistant;
    private final LlmUsageLogger usage;
    private final int maxTurns;

    public StrategyDiaryChat(TurnJudge turnJudge, StrategyPolicy policy, DiaryChatAssistant assistant,
            LlmUsageLogger usage, @Value("${momentory.strategy.max-turns:12}") int maxTurns) {
        this.turnJudge = turnJudge;
        this.policy = policy;
        this.assistant = assistant;
        this.usage = usage;
        this.maxTurns = maxTurns;
    }

    /**
     * @param userMessage 엔진이 방금 대화 기록에 넣은 사용자 발화
     * @param stopPhrase  방금 답이 분명한 종료 문구인가 — 판단 모델이 실패했을 때만 쓰는 안전망
     */
    Outcome turn(RetrospectState state, Message userMessage, String content, boolean stopPhrase) {
        boolean firstTurn = state.diaryTurn() == 0;
        Optional<TurnJudgment> judgment = turnJudge.judge(state, content);
        state.logJudgment(TurnJudgmentLog.of(userMessage.id(), state.diaryTurn() + 1, judgment.orElse(null)));
        StrategyPolicy.Decision decision = judgment
                .map(j -> policy.decide(j, state.strategySignals(), firstTurn))
                .orElseGet(() -> policy.decideWithoutJudgment(state.strategySignals(), firstTurn, stopPhrase));
        TurnStrategy strategy = decision.strategy();
        state.strategySignals(decision.signals());
        if (strategy.type() == StrategyType.CLOSE) {
            state.logStrategy(strategy, StrategyPolicy.VERSION, true);
            return new Outcome(Kind.CLOSE, null, SafetyLevel.NONE, List.of());
        }

        state.turnStrategy(strategy);
        Optional<DiaryTurn> ai = assistant.turn(state, content);
        state.turnStrategy(null);
        state.bumpDiaryTurn();

        // 안전 상한 — 전략이 무엇을 골랐든 여기서 정리한다. 이 턴의 전략은 '적용 안 됨'으로 남는다.
        boolean capped = state.diaryTurn() >= maxTurns;
        state.logStrategy(strategy, StrategyPolicy.VERSION, !capped);
        String reflection = "";
        String question = null;
        SafetyLevel safetyLevel = SafetyLevel.NONE;
        List<String> safetyFlags = List.of();
        if (ai.isPresent()) {
            DiaryTurn t = ai.get();
            safetyLevel = t.safetyLevelOrNone();
            safetyFlags = t.safetyFlags();
            // 사건 이름은 일기 대체문과 「다른 이야기」 질문을 고르는 데만 쓴다. 감정 · 계기 여부는 판단 모델이 본다.
            if (!t.eventPending()) {
                state.event(t.event());
                state.addSecondaryEvents(t.secondaryEvents());
                state.meaning(t.meaning());
            }
            reflection = withoutVocative(state, RetrospectEngine.empathyStatements(t.empathy()));
            question = withoutVocative(state, t.question());
        } else {
            state.logGenerationFailed();
            usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(), Phase.DIARY_CHAT.key());
        }
        if (capped) {
            return new Outcome(Kind.FINISH, reflection, safetyLevel, safetyFlags);
        }
        if (strategy.focus() == QuestionFocus.WRAP_UP) {
            return new Outcome(Kind.PROPOSE_WRAP_UP, reflection, safetyLevel, safetyFlags);
        }
        return new Outcome(Kind.REPLY, compose(state, strategy, reflection, question), safetyLevel, safetyFlags);
    }

    /** 받아주는 말 뒤에, 전략이 질문을 두면 그 초점의 질문 하나를 붙인다. */
    private static String compose(RetrospectState state, TurnStrategy strategy, String reflection,
            String question) {
        String ack = reflection.isBlank() ? PLAIN_ACK : reflection;
        if (!strategy.asks()) {
            return ack;
        }
        // 다른 기억을 묻는 문장은 고정이다 — 이 문장 뒤의 거절을 「다른 이야기 없음」으로 알아본다.
        String ask = strategy.focus() == QuestionFocus.OTHER_TOPIC ? RetrospectEngine.OTHER_MEMORY_QUESTION
                : questionFor(question, strategy.focus());
        // 「맞아」 같은 짧은 동의에는 앞의 공감을 되풀이하지 않고 바로 묻는다.
        return strategy.brief() ? ask : ack + "\n" + ask;
    }

    /**
     * 문장 맨 앞의 호칭("테스터님, ")을 뗀다 — 모델이 매 응답을 호칭으로 시작하고 질문에도 다시 붙였다.
     * 문장 가운데에 자연스럽게 들어간 호칭은 그대로 둔다.
     */
    static String withoutVocative(RetrospectState state, String text) {
        if (text == null || state.nickname() == null || state.nickname().isBlank()) {
            return text;
        }
        return text.strip().replaceFirst("^" + java.util.regex.Pattern.quote(state.nickname().strip()) + "\\s*님[,，]?\\s*", "");
    }

    /** 모델이 쓴 질문 한 문장을 쓰고, 질문이 없으면 초점에 맞는 고정 질문으로 대신한다. */
    private static String questionFor(String candidate, QuestionFocus focus) {
        if (candidate != null) {
            for (String part : candidate.strip().split("(?<=[.!?。！？])\\s*|\\R")) {
                String sentence = part.strip();
                if (sentence.endsWith("?") || sentence.endsWith("？")) {
                    return sentence;
                }
            }
        }
        return switch (focus) {
            case EVENT -> "그때 무슨 일이 있었는지 조금만 더 들려주실래요?";
            case FEELING -> "그때 스스로에게는 어떤 마음이 드셨나요?";
            case MEANING -> "그 일이 유독 크게 느껴진 이유가 있을까요?";
            case FOLLOW_UP -> "방금 말씀하신 부분을 조금만 더 들려주실래요?";
            case EVIDENCE -> "그렇게 느끼신 건 어떤 부분 때문이었을까요?";
            default -> "그 일이 본인에게는 어땠나요?";
        };
    }
}
