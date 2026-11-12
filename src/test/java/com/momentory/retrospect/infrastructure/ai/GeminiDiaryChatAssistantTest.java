package com.momentory.retrospect.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.DiaryTurn;

class GeminiDiaryChatAssistantTest {
    private final GeminiApiClient client = mock(GeminiApiClient.class);
    private GeminiDiaryChatAssistant assistant;
    private final DiaryTurn turn = new DiaryTurn("친구가 생일을 챙겨줬다", List.of(), null, true,
            "친구가 기억하고 챙겨준 마음이 고맙게 느껴지셨겠어요.",
            "친구가 기억하고 챙겨준 마음이 고맙게 느껴지셨겠어요.", "none", List.of(), false, false, false, false, false);

    @BeforeEach
    void setUp() {
        when(client.withReadTimeout(GeminiDiaryChatAssistant.TURN_TIMEOUT)).thenReturn(client);
        assistant = new GeminiDiaryChatAssistant(client, new PromptFactory(2000, EmotionPromptVariant.ZERO_SHOT));
    }

    @Test
    void retriesTransientFailureOnceAndUsesContextualEmpathy() {
        when(client.attempt(any(), eq(DiaryTurn.class), any()))
                .thenReturn(GeminiApiClient.Attempt.failed(true))
                .thenReturn(new GeminiApiClient.Attempt<>(Optional.of(turn), false));

        assertThat(assistant.turn(new RetrospectState("retry"), "친구가 생일을 챙겨줘 고마웠어요."))
                .contains(turn);
        verify(client, times(2)).attempt(any(), eq(DiaryTurn.class), any());
    }

    @Test
    void doesNotRetrySuccessfulResponse() {
        when(client.attempt(any(), eq(DiaryTurn.class), any()))
                .thenReturn(new GeminiApiClient.Attempt<>(Optional.of(turn), false));
        assertThat(assistant.turn(new RetrospectState("success"), "고마웠어요.")).contains(turn);
        verify(client).attempt(any(), eq(DiaryTurn.class), any());
    }

    @Test
    void doesNotRetryNonRetryableFailure() {
        when(client.attempt(any(), eq(DiaryTurn.class), any()))
                .thenReturn(GeminiApiClient.Attempt.failed(false));
        assertThat(assistant.turn(new RetrospectState("blocked"), "사용자 답변")).isEmpty();
        verify(client).attempt(any(), eq(DiaryTurn.class), any());
    }

    @Test
    void stopsAfterTwoTransientFailures() {
        when(client.attempt(any(), eq(DiaryTurn.class), any()))
                .thenReturn(GeminiApiClient.Attempt.failed(true));
        assertThat(assistant.turn(new RetrospectState("failure"), "사용자 답변")).isEmpty();
        verify(client, times(2)).attempt(any(), eq(DiaryTurn.class), any());
    }

    @Test
    void schemaRequiresEmpathyAndAllPrimitiveDecisions() {
        assertThat(GeminiResponseSchemas.forType(DiaryTurn.class).get("required"))
                .isEqualTo(List.of("question", "empathy", "emotionPresent", "noMoreToAsk",
                        "offTopic", "vague", "eventPending", "emotionCausePresent"));
    }
}
