package com.momentory.retrospect.infrastructure.ai;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.momentory.retrospect.domain.Need;
import com.momentory.retrospect.domain.Needs;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.ExplorationAssistant;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiActions;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiNeeds;

/**
 * 감정 탐색 후보 어댑터 — 바람(욕구)은 고정 목록에서 고르고(G2, 목록 밖 단어는 버림), 작은 행동은
 * 맥락에 맞게 만든다(G2). 실패하면 빈 목록(엔진이 폴백으로 내려간다).
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

    @Override
    public List<String> suggestActions(RetrospectState state) {
        LlmRequest request = LlmRequest.of(state, LlmRole.G2, prompts.system(),
                prompts.actionsPrompt(state));
        List<String> actions = llm.generate(request, GeminiActions.class)
                .map(GeminiActions::actions)
                .orElse(List.of());
        return actions == null ? List.of() : actions;
    }
}
