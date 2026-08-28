package com.momentory.retrospect.infrastructure.ai;

import java.util.List;

import org.springframework.stereotype.Component;

import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.ExtractedEmotion;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.EmotionExtractor;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiEmotion;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiEmotions;

/**
 * 감정 추출 어댑터 — 일기 작성 끝에 대화 전체를 근거로 한 번(G1) 호출한다. normalized 키는 고정
 * 10종 {@link Emotion} 으로 매핑하고(모르는 키는 null), 실패하면 빈 목록.
 *
 * <p>향후 전용 감정 분류 모델로 교체할 자리 — 대화 엔진은 이 경계만 안다.
 */
@Component
public class GeminiEmotionExtractor implements EmotionExtractor {

    private final GeminiApiClient llm;
    private final PromptFactory prompts;

    public GeminiEmotionExtractor(GeminiApiClient llm, PromptFactory prompts) {
        this.llm = llm;
        this.prompts = prompts;
    }

    @Override
    public List<ExtractedEmotion> extract(RetrospectState state) {
        LlmRequest request = LlmRequest.of(state, LlmRole.G1, prompts.system(),
                prompts.emotionExtractPrompt(state));
        List<GeminiEmotion> raw = llm.generate(request, GeminiEmotions.class)
                .map(GeminiEmotions::emotions)
                .orElse(List.of());
        if (raw == null) {
            return List.of();
        }
        return raw.stream().map(GeminiEmotionExtractor::toExtracted).toList();
    }

    private static ExtractedEmotion toExtracted(GeminiEmotion e) {
        Emotion normalized = Emotion.fromKey(e.normalized()).orElse(null);
        return new ExtractedEmotion(e.raw(), normalized, e.timing(), e.cause(), e.evidence());
    }
}
