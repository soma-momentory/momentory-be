package com.momentory.retrospect.infrastructure.ai;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.StrategyReply;
import com.momentory.retrospect.domain.assistant.StrategyReplyWriter;
import com.momentory.retrospect.domain.strategy.TurnStrategy;

/**
 * 전략 대화의 문장 생성 어댑터 — 한 번의 Gemini(G2) 호출로 받아주는 말 · 질문 · 흐름 신호를 받는다.
 * 일시적인 실패에는 한 번 재시도한다. 두 번 모두 실패하면 empty.
 */
@Component
public class GeminiStrategyReplyWriter implements StrategyReplyWriter {

    private final GeminiApiClient llm;
    private final PromptFactory prompts;

    public GeminiStrategyReplyWriter(GeminiApiClient llm, PromptFactory prompts) {
        // 사용자가 기다리는 턴이라 읽기 대기를 짧게 둔다(일기 작성 턴과 같은 한도).
        this.llm = llm.withReadTimeout(GeminiDiaryChatAssistant.TURN_TIMEOUT);
        this.prompts = prompts;
    }

    @Override
    public Optional<StrategyReply> write(RetrospectState state, String userText, TurnStrategy strategy) {
        LlmRequest request = LlmRequest.of(state, LlmRole.G2, prompts.strategySystem(),
                prompts.strategyTurnPrompt(state, userText, strategy));
        var schema = GeminiResponseSchemas.forType(StrategyReply.class);
        var first = llm.attempt(request, StrategyReply.class, schema);
        if (first.value().isPresent() || !first.retryable()) {
            return first.value();
        }
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        return llm.attempt(request, StrategyReply.class, schema).value();
    }
}
