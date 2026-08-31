package com.momentory.retrospect.infrastructure.ai;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.ExtractedTopic;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.TopicType;
import com.momentory.retrospect.domain.assistant.TopicExtractor;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiTopic;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiTopics;

/**
 * 토픽 추출 어댑터(G5) — 일기 작성 끝에 대화 전체를 근거로 한 번 호출한다. 주 일정·키워드를 뽑고
 * 각 항목에 매칭된 감정 키를 고정 10종 {@link Emotion} 으로 매핑한다(모르는 키는 버린다).
 * label 이 비었거나 파싱이 안 되면 그 항목은 버리고, 실패하면 빈 목록.
 *
 * <p>향후 전용 추출기로 교체할 자리 — 대화 엔진/서비스는 이 경계만 안다.
 */
@Component
public class GeminiTopicExtractor implements TopicExtractor {

    private final GeminiApiClient llm;
    private final PromptFactory prompts;

    public GeminiTopicExtractor(GeminiApiClient llm, PromptFactory prompts) {
        this.llm = llm;
        this.prompts = prompts;
    }

    @Override
    public List<ExtractedTopic> extract(RetrospectState state) {
        LlmRequest request = LlmRequest.of(state, LlmRole.G5, prompts.system(),
                prompts.topicExtractPrompt(state));
        List<GeminiTopic> raw = llm.generate(request, GeminiTopics.class)
                .map(GeminiTopics::topics)
                .orElse(List.of());
        if (raw == null) {
            return List.of();
        }
        return raw.stream().map(GeminiTopicExtractor::toTopic).filter(Objects::nonNull).toList();
    }

    private static ExtractedTopic toTopic(GeminiTopic t) {
        if (t == null || t.label() == null || t.label().isBlank()) {
            return null;
        }
        TopicType type = "SCHEDULE".equalsIgnoreCase(t.type()) ? TopicType.SCHEDULE
                : TopicType.KEYWORD;
        List<String> keys = t.emotions() == null ? List.of() : t.emotions();
        List<Emotion> emotions = keys.stream()
                .map(k -> Emotion.fromKey(k).orElse(null))
                .filter(Objects::nonNull)
                .toList();
        return new ExtractedTopic(type, t.label().strip(), emotions);
    }
}
