package com.momentory.retrospect.infrastructure.ai;

import java.util.Optional;
import java.time.Duration;

import org.springframework.stereotype.Component;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.DiaryChatAssistant;
import com.momentory.retrospect.domain.assistant.DiaryTurn;

/**
 * 일기 작성 턴 어댑터 — 한 번의 Gemini(G2) 호출로 슬롯 추출 + 다음 질문을 받는다.
 * 일시적인 실패에는 한 번 재시도한다. 두 번 모두 실패하면 empty로 엔진의 맥락 공감에 맡긴다.
 */
@Component
public class GeminiDiaryChatAssistant implements DiaryChatAssistant {

    static final Duration TURN_TIMEOUT = Duration.ofSeconds(8);
    private final GeminiApiClient llm;
    private final PromptFactory prompts;

    public GeminiDiaryChatAssistant(GeminiApiClient llm, PromptFactory prompts) {
        // G2의 읽기 대기만 제한한다. 마지막 턴의 G1·G4 호출 시간은 별도다(회고 API 한도 45초).
        this.llm = llm.withReadTimeout(TURN_TIMEOUT);
        this.prompts = prompts;
    }

    @Override
    public Optional<DiaryTurn> turn(RetrospectState state, String userText) {
        // 서버가 이번 턴의 전략을 골랐으면 그 전략에 맞춘 프롬프트를, 아니면 기존 고정 순서 프롬프트를 쓴다.
        String prompt = state.turnStrategy() == null ? prompts.diaryTurnPrompt(state, userText)
                : prompts.strategyTurnPrompt(state, userText, state.turnStrategy());
        LlmRequest request = LlmRequest.of(state, LlmRole.G2, prompts.system(), prompt);
        var schema = GeminiResponseSchemas.forType(DiaryTurn.class);
        var first = llm.attempt(request, DiaryTurn.class, schema);
        if (first.value().isPresent() || !first.retryable()) {
            return first.value();
        }
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        return llm.attempt(request, DiaryTurn.class, schema).value();
    }
}
