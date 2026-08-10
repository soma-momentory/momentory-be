package com.momentory.retrospect.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmotionTest {

    @Test
    @DisplayName("9종이 원본 순서 그대로다 — 클라이언트 번호 선택이 이 순서에 의존한다")
    void declarationOrderMatchesOriginal() {
        assertThat(Emotion.keys()).containsExactly(
                "anxious", "depressed", "angry", "happy", "stuck",
                "lethargic", "tired", "proud", "calm");
    }

    @Test
    @DisplayName("라벨이 원본과 같다")
    void labelsMatchOriginal() {
        assertThat(Emotion.ANXIOUS.label()).isEqualTo("불안함");
        assertThat(Emotion.ANGRY.label()).isEqualTo("화남/답답");
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
