package com.momentory.retrospect.infrastructure.ai;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.DiaryChatAssistant;
import com.momentory.retrospect.domain.assistant.DiaryTurn;

/**
 * 일기 작성 턴 어댑터 — 한 번의 Gemini(G2) 호출로 슬롯 추출 + 다음 질문을 받는다.
 * 실패하면 empty(엔진이 슬롯 폴백 질문으로 내려간다).
 */
@Component
public class GeminiDiaryChatAssistant implements DiaryChatAssistant {

    private final GeminiApiClient llm;
    private final PromptFactory prompts;

    public GeminiDiaryChatAssistant(GeminiApiClient llm, PromptFactory prompts) {
        this.llm = llm;
        this.prompts = prompts;
    }

    @Override
    public Optional<DiaryTurn> turn(RetrospectState state, String userText) {
        LlmRequest request = LlmRequest.of(state, LlmRole.G2, prompts.system(),
                prompts.diaryTurnPrompt(state, userText));
        return llm.generate(request, DiaryTurn.class);
    }
}
