package com.momentory.retrospect.infrastructure.ai;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.DiaryOutput;
import com.momentory.retrospect.domain.assistant.DiaryWriter;

/**
 * 일기 생성 어댑터 — 대화 전체를 사용자가 말한 사실·감정만으로 5~6문장·맥락별 문단의 1인칭 일기로 정리한다(G4).
 * 분량을 벗어나면 한 번만 검토한다. 최초 생성 실패는 empty(엔진의 최소 일기 대체), 검토 실패는 최초 초안을 유지한다.
 */
@Component
public class GeminiDiaryWriter implements DiaryWriter {

    static final Duration REVISION_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?。！？]+(?=\\s|$)");

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
        Optional<DiaryOutput> first = llm.generate(request, DiaryOutput.class);
        if (first.isEmpty() || !first.get().hasDiary() || hasTargetSentenceCount(first.get().diary())) {
            return first;
        }
        // 추가 경험을 만들지 않고 기존 재료를 재구성한다. 한 번만 검토하며 실패해도 최초 초안을 잃지 않는다.
        String revision = request.user() + """

                [분량 검토]
                아래 초안은 5~6문장 범위를 벗어났습니다. 원래 대화에 있는 행동·감정만 재구성해
                5~6문장으로 다듬고, 맥락별 문단을 유지하세요. 새 사실·감정이나 반복 문장으로 채우지 마세요.
                재료가 부족하면 정확성을 우선해 짧은 일기를 유지하세요.
                [초안]
                """ + first.get().diary();
        return llm.withReadTimeout(REVISION_TIMEOUT)
                .generate(LlmRequest.of(state, LlmRole.G4, prompts.system(), revision), DiaryOutput.class)
                .filter(DiaryOutput::hasDiary)
                .or(() -> first);
    }

    private static boolean hasTargetSentenceCount(String diary) {
        long sentences = SENTENCE_END.matcher(diary).results().count();
        return sentences >= 5 && sentences <= 6;
    }
}
