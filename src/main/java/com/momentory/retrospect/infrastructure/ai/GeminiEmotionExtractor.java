package com.momentory.retrospect.infrastructure.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.EmotionPhase;
import com.momentory.retrospect.domain.ExtractedEmotion;
import com.momentory.retrospect.domain.ExtractedEvent;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.EmotionExtraction;
import com.momentory.retrospect.domain.assistant.EmotionExtractor;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiEmotion;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiEvent;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiExtraction;

/**
 * 추출 어댑터 — 일기 작성 끝에 대화 전체를 근거로 <b>한 번(G1)</b> 호출해 사건(≤2)과 감정을
 * 함께 받는다 (모델 비교 계획 §3.1, §3.4). 실패하면 빈 결과.
 *
 * <p>모델 출력을 그대로 믿지 않고 어댑터에서 정리한다:
 * <ul>
 *   <li>사건은 요약이 있는 것만, 최대 {@link RetrospectState#MAX_EVENTS} 개.</li>
 *   <li>감정의 {@code normalized}·{@code phase} 는 모르는 값이면 null(도메인 타입으로만 매핑).</li>
 *   <li>{@code eventId} 가 실제 사건 id 를 가리키지 않으면 null 로 떨군다 — 유령 참조를 남기면
 *       채점에서 사건 귀속(Event Attribution)이 오염된다.</li>
 * </ul>
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
    public EmotionExtraction extract(RetrospectState state) {
        LlmRequest request = LlmRequest.of(state, LlmRole.G1, prompts.system(),
                prompts.emotionExtractPrompt(state));
        GeminiExtraction raw = llm.generate(request, GeminiExtraction.class).orElse(null);
        if (raw == null) {
            return EmotionExtraction.empty();
        }
        List<ExtractedEvent> events = toEvents(raw.events());
        Set<Integer> eventIds = events.stream().map(ExtractedEvent::id).collect(Collectors.toSet());
        return new EmotionExtraction(events, toEmotions(raw.emotions(), eventIds));
    }

    private static List<ExtractedEvent> toEvents(List<GeminiEvent> raw) {
        if (raw == null) {
            return List.of();
        }
        List<ExtractedEvent> events = new ArrayList<>();
        for (GeminiEvent e : raw) {
            if (e == null || e.summary() == null || e.summary().isBlank()) {
                continue;
            }
            // id 가 없으면 순서대로 매긴다 — eventId 참조가 끊기지 않도록.
            int id = e.id() == null ? events.size() + 1 : e.id();
            events.add(new ExtractedEvent(id, e.label(), e.summary(), e.evidence()));
            if (events.size() >= RetrospectState.MAX_EVENTS) {
                break;
            }
        }
        return events;
    }

    private static List<ExtractedEmotion> toEmotions(List<GeminiEmotion> raw, Set<Integer> eventIds) {
        if (raw == null) {
            return List.of();
        }
        List<ExtractedEmotion> emotions = new ArrayList<>();
        for (GeminiEmotion e : raw) {
            if (e == null) {
                continue;
            }
            Integer eventId = eventIds.contains(e.eventId()) ? e.eventId() : null;
            emotions.add(new ExtractedEmotion(
                    eventId,
                    e.raw(),
                    Emotion.fromKey(e.normalized()).orElse(null),
                    e.intensity(),
                    EmotionPhase.fromKey(e.phase()).orElse(null),
                    e.evidence(),
                    e.evidenceIds()));
        }
        return emotions;
    }
}
