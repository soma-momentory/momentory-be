package com.momentory.retrospect.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.momentory.retrospect.NeedActionCatalogFixture;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.ExtractedEvent;
import com.momentory.retrospect.domain.Needs;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.infrastructure.ai.GeminiStructuredOutputs.GeminiActions;

class GeminiExplorationAssistantTest {

    private final GeminiApiClient llm = mock(GeminiApiClient.class);
    private final GeminiExplorationAssistant assistant = new GeminiExplorationAssistant(llm,
            new PromptFactory(2000, EmotionPromptVariant.ZERO_SHOT), NeedActionCatalogFixture.load());

    @Test
    void choosesFromNeedLinkedCandidatesAndReturnsApprovedTitles() {
        var state = state("평안");
        when(llm.generate(any(), eq(GeminiActions.class)))
                .thenReturn(Optional.of(new GeminiActions(List.of("A03", "A03", "A56", "A99", "A02"))));

        assertThat(assistant.suggestActions(state)).containsExactly(
                "20분 동안 마음이 편한 장소 구체적으로(풍경, 소리, 촉감) 상상하기",
                "20분 동안 근육에 힘을 줬다가 천천히 풀기");

        var request = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llm).generate(request.capture(), eq(GeminiActions.class));
        assertThat(request.getValue().user()).contains("[사건] 공원에서 혼자 쉬고 싶었던 일",
                "[감정] 답답함", "[바람] 평안", "A03 ·", "20분 안내 심상 단회")
                .doesNotContain("A56 ·", "A33 ·", "A53 ·", "A57 ·");
    }

    @Test
    void failureOrNoMatchingCandidateDoesNotReturnGenericActions() {
        when(llm.generate(any(), eq(GeminiActions.class))).thenReturn(Optional.empty());
        assertThat(assistant.suggestActions(state("휴식"))).isEmpty();

        when(llm.generate(any(), eq(GeminiActions.class)))
                .thenReturn(Optional.of(new GeminiActions(List.of("A33", "따뜻한 물 마시기"))));
        assertThat(assistant.suggestActions(state("휴식"))).isEmpty();
    }

    @Test
    void modelMayReturnNoActionsInsteadOfFillingThreeSlots() {
        when(llm.generate(any(), eq(GeminiActions.class)))
                .thenReturn(Optional.of(new GeminiActions(List.of())));
        assertThat(assistant.suggestActions(state("정의"))).isEmpty();

        when(llm.generate(any(), eq(GeminiActions.class)))
                .thenReturn(Optional.of(new GeminiActions(null)));
        assertThat(assistant.suggestActions(state("정의"))).isEmpty();
    }

    @Test
    void unconfirmedOrUnmappedNeedDoesNotInvokeModelOrGuessAConnection() {
        var state = new RetrospectState("unconfirmed");
        state.desiredState("눈치 보지 않고 지내고 싶어요");
        state.restMethods(List.of("음악 듣기"));

        assertThat(assistant.suggestActions(state)).isEmpty();
        verifyNoInteractions(llm);
    }

    private static RetrospectState state(String need) {
        var state = new RetrospectState("s1");
        state.events(List.of(new ExtractedEvent(1, "공원", "공원에서 혼자 쉬고 싶었던 일", List.of(1))));
        state.confirmEmotions(List.of(Emotion.FRUSTRATED));
        state.chooseNeeds(List.of(Needs.byWord(need).orElseThrow()));
        return state;
    }
}
