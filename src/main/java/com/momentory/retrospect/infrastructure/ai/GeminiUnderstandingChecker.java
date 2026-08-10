package com.momentory.retrospect.infrastructure.ai;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.UnderstandingCheck;
import com.momentory.retrospect.domain.assistant.UnderstandingChecker;

/** AI-G1 어댑터 — 1턴 답변의 이해 확인. */
@Component
public class GeminiUnderstandingChecker implements UnderstandingChecker {

    private final GeminiApiClient llm;
    private final PromptFactory prompts;

    public GeminiUnderstandingChecker(GeminiApiClient llm, PromptFactory prompts) {
        this.llm = llm;
        this.prompts = prompts;
    }

    @Override
    public Optional<UnderstandingCheck> check(RetrospectState state, String firstAnswer) {
        LlmRequest request = LlmRequest.of(state, LlmRole.G1,
                prompts.system(state),
                prompts.understandingPrompt(state, firstAnswer));

        return llm.generate(request, UnderstandingCheck.class)
                .filter(r -> r.reflection() != null && !r.reflection().isBlank());
    }
}
