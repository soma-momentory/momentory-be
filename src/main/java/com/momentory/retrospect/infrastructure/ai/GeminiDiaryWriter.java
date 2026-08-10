package com.momentory.retrospect.infrastructure.ai;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.DiaryOutput;
import com.momentory.retrospect.domain.assistant.DiaryWriter;

/** AI-G4 어댑터 — 일기(그냥 + 리프레이밍)를 한 호출로 받는다. */
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
        LlmRequest request = LlmRequest.of(state, LlmRole.G4,
                prompts.system(state),
                prompts.diaryPrompt(state));

        Optional<DiaryOutput> result = llm.generate(request, DiaryOutput.class)
                .filter(DiaryOutput::hasDiary);

        // 짧은 기록형은 리프레이밍 일기를 만들지 않는다 — 모델이 채워 보냈어도 버린다.
        if (result.isPresent() && !state.mode().hasReframedDiary()) {
            return Optional.of(new DiaryOutput(result.get().diary(), null));
        }
        return result;
    }
}
