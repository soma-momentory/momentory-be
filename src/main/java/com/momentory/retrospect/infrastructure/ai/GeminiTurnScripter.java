package com.momentory.retrospect.infrastructure.ai;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.TurnScript;
import com.momentory.retrospect.domain.assistant.TurnScripter;
import com.momentory.retrospect.domain.script.ScriptStep;

/**
 * AI-G2 어댑터 — 스크립트 턴 문구·선택지 생성.
 *
 * <p>선택지 턴인데 보기 개수가 어긋나면 실패로 취급한다 — 엔진이 폴백(PDF 원형 보기)으로 내려간다.
 */
@Component
public class GeminiTurnScripter implements TurnScripter {

    private final GeminiApiClient llm;
    private final PromptFactory prompts;

    public GeminiTurnScripter(GeminiApiClient llm, PromptFactory prompts) {
        this.llm = llm;
        this.prompts = prompts;
    }

    @Override
    public Optional<TurnScript> script(RetrospectState state, ScriptStep step) {
        LlmRequest request = LlmRequest.of(state, LlmRole.G2,
                prompts.system(state),
                prompts.turnPrompt(state, step));

        return llm.generate(request, TurnScript.class)
                .filter(TurnScript::hasMessage)
                .filter(r -> !step.isChoice() || r.options().size() == step.optionCount());
    }
}
