package com.momentory.retrospect.infrastructure.ai;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.DiaryOutput;
import com.momentory.retrospect.domain.assistant.DiaryWriter;

/**
 * 일기 생성 어댑터 — 대화 전체를 사용자가 말한 사실·감정만으로 5~6줄 1인칭 일기로 정리한다(G4).
 * 실패하면 empty(엔진이 슬롯만으로 최소 일기를 조립한다).
 */
@Component
public class GeminiDiaryWriter implements DiaryWriter {

    private final GeminiApiClient llm;
    private final PromptFactory prompts;

    public GeminiDiaryWriter(GeminiApiClient llm, PromptFactory prompts) {
        this.llm = llm;
        this.prompts = prompts;
    }

    @Override
    public Optional<DiaryOutput> write(RetrospectState state) {
        LlmRequest request = LlmRequest.of(state, LlmRole.G4, prompts.system(),
                prompts.diaryPrompt(state));
        return llm.generate(request, DiaryOutput.class);
    }
}
