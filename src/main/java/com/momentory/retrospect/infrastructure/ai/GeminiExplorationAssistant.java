package com.momentory.retrospect.infrastructure.ai;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.momentory.retrospect.domain.Need;
import com.momentory.retrospect.domain.Needs;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.ExplorationAssistant;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiNeeds;

/**
 * 바람은 고정 욕구 목록에서만 고른다. 작은 행동은 회고가 아니라 바바의 편지에서 고른다(편지 PRD 14-1).
 */
@Component
public class GeminiExplorationAssistant implements ExplorationAssistant {

    private final GeminiApiClient llm;
    private final PromptFactory prompts;

    public GeminiExplorationAssistant(GeminiApiClient llm, PromptFactory prompts) {
        this.llm = llm;
        this.prompts = prompts;
    }

    @Override
    public List<Need> suggestNeeds(RetrospectState state) {
        LlmRequest request = LlmRequest.of(state, LlmRole.G2, prompts.system(),
                prompts.needsPrompt(state));
        List<String> words = llm.generate(request, GeminiNeeds.class)
                .map(GeminiNeeds::words)
                .orElse(List.of());
        if (words == null) {
            return List.of();
        }
        // 고정 목록으로 검증 — 목록 밖 단어(환각)는 버린다.
        return words.stream().map(Needs::byWord).flatMap(Optional::stream).toList();
    }
}
