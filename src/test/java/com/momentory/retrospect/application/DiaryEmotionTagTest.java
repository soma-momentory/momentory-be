package com.momentory.retrospect.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.EmotionPhase;
import com.momentory.retrospect.domain.ExtractedEmotion;
import com.momentory.retrospect.domain.RetrospectState;

/**
 * 일기 감정 태그의 우선순위 — 확인 감정 → 추출 감정 → 추론 감정.
 *
 * <p>추론 감정은 <b>다른 둘이 모두 비었을 때만</b> 쓴다. 사실만 말한 대화에서 사용자가 감정
 * 탐색까지 건너뛰면 일기가 감정 없이 남던 자리다.
 */
class DiaryEmotionTagTest {

    private static RetrospectState state() {
        RetrospectState state = new RetrospectState("sess-1");
        state.begin("개발", null, 77L, "정민", "취업");
        return state;
    }

    private static ExtractedEmotion extracted(Emotion normalized) {
        return new ExtractedEmotion(1, normalized.label(), normalized, 3,
                EmotionPhase.DURING, "그랬어요", List.of(1));
    }

    @Test
    @DisplayName("사실만 말하고 감정 탐색까지 건너뛰면 추론 감정이 일기 태그를 채운다")
    void inferredEmotionFillsWhenNothingElseExists() {
        RetrospectState state = state();
        state.inferredEmotion(Emotion.STUCK);

        assertThat(RetrospectService.emotionTags(state)).containsExactly(Emotion.STUCK);
    }

    @Test
    @DisplayName("추출된 감정이 있으면 추론 감정은 태그에 끼지 않는다")
    void extractedEmotionWins() {
        RetrospectState state = state();
        state.emotions(List.of(extracted(Emotion.ANGRY)));
        state.inferredEmotion(Emotion.STUCK);

        assertThat(RetrospectService.emotionTags(state)).containsExactly(Emotion.ANGRY);
    }

    @Test
    @DisplayName("사용자가 고른 감정이 있으면 추론 감정은 태그에 끼지 않는다")
    void confirmedEmotionWins() {
        RetrospectState state = state();
        state.confirmEmotions(List.of(Emotion.HAPPY));
        state.inferredEmotion(Emotion.STUCK);

        assertThat(RetrospectService.emotionTags(state)).containsExactly(Emotion.HAPPY);
    }

    @Test
    @DisplayName("추론 감정도 없으면 감정 없는 일기로 남는다")
    void noEmotionAtAll() {
        assertThat(RetrospectService.emotionTags(state())).isEmpty();
    }
}
