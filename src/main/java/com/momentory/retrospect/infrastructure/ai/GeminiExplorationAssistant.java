package com.momentory.retrospect.infrastructure.ai;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.momentory.retrospect.domain.Need;
import com.momentory.retrospect.domain.NeedActionCatalog;
import com.momentory.retrospect.domain.Needs;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.ActionSuggestions;
import com.momentory.retrospect.domain.assistant.ExplorationAssistant;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiActions;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiNeeds;

/**
 * 바람은 고정 욕구 목록에서, 작은 행동은 해당 욕구에 연결된 노션 후보에서만 고른다.
 * 행동 선정 실패와 정상적인 빈 결과를 구분한다. 목록 밖 행동으로 대체하지 않는다.
 */
@Component
public class GeminiExplorationAssistant implements ExplorationAssistant {

    private final GeminiApiClient llm;
    private final PromptFactory prompts;
    private final NeedActionCatalog catalog;

    public GeminiExplorationAssistant(GeminiApiClient llm, PromptFactory prompts, NeedActionCatalog catalog) {
        this.llm = llm;
        this.prompts = prompts;
        this.catalog = catalog;
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
    public ActionSuggestions suggestActions(RetrospectState state) {
        if (catalog.candidatesFor(state.needs()).isEmpty()) {
            return ActionSuggestions.success(List.of());
        }
        LlmRequest request = LlmRequest.of(state, LlmRole.G2, prompts.system(),
                prompts.actionsPrompt(state, catalog));
        Optional<GeminiActions> result = llm.generate(request, GeminiActions.class);
        if (result.isEmpty() || result.get().actionIds() == null) {
            return ActionSuggestions.failure();
        }
        List<String> ids = result.get().actionIds();
        List<String> selected = catalog.selectedTitles(state.needs(), ids);
        if (!ids.isEmpty() && selected.isEmpty()) {
            return ActionSuggestions.failure();
        }
        return ActionSuggestions.success(selected);
    }
}
