package com.momentory.retrospect.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.ExtractedEvent;
import com.momentory.retrospect.domain.Need;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiNeeds;

/** 바람(욕구) 후보 — 고정 목록 밖 단어는 버린다. 작은 행동은 회고가 아니라 편지에서 고른다. */
class GeminiExplorationAssistantTest {

    private final GeminiApiClient llm = mock(GeminiApiClient.class);
    private final GeminiExplorationAssistant assistant = new GeminiExplorationAssistant(llm,
            new PromptFactory(2000, EmotionPromptVariant.ZERO_SHOT));

    @Test
    void keepsOnlyFixedNeedWords() {
        when(llm.generate(any(), eq(GeminiNeeds.class)))
                .thenReturn(Optional.of(new GeminiNeeds(List.of("휴식", "아무단어", " 인정 "))));

        assertThat(assistant.suggestNeeds(state())).extracting(Need::word).containsExactly("휴식", "인정");
    }

    @Test
    void failureOrNullWordsGiveNoCandidates() {
        when(llm.generate(any(), eq(GeminiNeeds.class))).thenReturn(Optional.empty());
        assertThat(assistant.suggestNeeds(state())).isEmpty();

        when(llm.generate(any(), eq(GeminiNeeds.class))).thenReturn(Optional.of(new GeminiNeeds(null)));
        assertThat(assistant.suggestNeeds(state())).isEmpty();
    }

    private static RetrospectState state() {
        var state = new RetrospectState("s1");
        state.events(List.of(new ExtractedEvent(1, "공원", "공원에서 혼자 쉬고 싶었던 일", List.of(1))));
        state.confirmEmotions(List.of(Emotion.FRUSTRATED));
        return state;
    }
}
