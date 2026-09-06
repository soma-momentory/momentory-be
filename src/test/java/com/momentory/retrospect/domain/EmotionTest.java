package com.momentory.retrospect.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmotionTest {

    @Test
    @DisplayName("10종 — v2 에서 화남/답답이 화남·답답함으로 분리됐다")
    void declarationOrder() {
        assertThat(Emotion.keys()).containsExactly(
                "anxious", "depressed", "angry", "frustrated", "happy", "stuck",
                "lethargic", "tired", "proud", "calm");
    }

    @Test
    @DisplayName("라벨 — 화남과 답답함이 분리됐다")
    void labels() {
        assertThat(Emotion.ANXIOUS.label()).isEqualTo("불안함");
        assertThat(Emotion.ANGRY.label()).isEqualTo("화남");
        assertThat(Emotion.FRUSTRATED.label()).isEqualTo("답답함");
        assertThat(Emotion.LETHARGIC.label()).isEqualTo("무기력");
    }

    @Test
    @DisplayName("긍정 감정 3종이 강점 신호로 쓰인다")
    void positiveEmotions() {
        assertThat(Emotion.POSITIVE)
                .containsExactlyInAnyOrder(Emotion.HAPPY, Emotion.PROUD, Emotion.CALM);
        assertThat(Emotion.PROUD.isPositive()).isTrue();
        assertThat(Emotion.ANXIOUS.isPositive()).isFalse();
    }

    @Test
    @DisplayName("알 수 없는 키는 라벨 변환에서 그대로 통과한다 (원본 label() 동작)")
    void unknownKeyPassesThrough() {
        assertThat(Emotion.labelOf("nonsense")).isEqualTo("nonsense");
        assertThat(Emotion.labelOf(null)).isNull();
    }

    @Test
    void validation() {
        assertThat(Emotion.isValid("anxious")).isTrue();
        assertThat(Emotion.isValid("nonsense")).isFalse();
        assertThat(Emotion.isValid(null)).isFalse();
        assertThat(Emotion.fromKey("calm")).contains(Emotion.CALM);
        assertThat(Emotion.fromKey("nope")).isEmpty();
    }
}
