package com.momentory.retrospect.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 추출 감정 값 객체 — 모델이 준 값을 그대로 믿지 않고 여기서 정리한다 (모델 비교 계획 §3.1).
 */
class ExtractedEmotionTest {

    private static ExtractedEmotion withIntensity(Integer intensity) {
        return new ExtractedEmotion(1, "불안했어요", Emotion.ANXIOUS, intensity,
                EmotionPhase.BEFORE, "너무 불안했어요", List.of(2));
    }

    private static ExtractedEmotion withEvidenceIds(List<Integer> ids) {
        return new ExtractedEmotion(1, "불안했어요", Emotion.ANXIOUS, 3,
                EmotionPhase.BEFORE, "너무 불안했어요", ids);
    }

    @Test
    @DisplayName("강도는 0~4 만 남기고 범위를 벗어나면 버린다 — 경계로 붙이지 않는다")
    void dropsIntensityOutOfRange() {
        assertThat(withIntensity(0).intensity()).isZero();
        assertThat(withIntensity(4).intensity()).isEqualTo(4);
        assertThat(withIntensity(5).intensity()).isNull();
        assertThat(withIntensity(-1).intensity()).isNull();
        assertThat(withIntensity(null).intensity()).isNull();
    }

    @Test
    @DisplayName("근거 발화 번호는 중복을 지우고 오름차순으로 정렬한다 — 채점의 집합 연산 전제")
    void normalizesEvidenceIds() {
        assertThat(withEvidenceIds(List.of(3, 1, 3, 2)).evidenceIds()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("0 이하이거나 null 인 발화 번호는 버린다 — 발화 번호는 1부터다")
    void dropsInvalidEvidenceIds() {
        assertThat(withEvidenceIds(Arrays.asList(0, -2, null, 1)).evidenceIds()).containsExactly(1);
        assertThat(withEvidenceIds(null).evidenceIds()).isEmpty();
    }

    @Test
    @DisplayName("원문 표현만으로 만들면 나머지는 비어 있다")
    void ofRawLeavesRestEmpty() {
        ExtractedEmotion e = ExtractedEmotion.ofRaw("  무시당한 느낌  ");

        assertThat(e.raw()).isEqualTo("무시당한 느낌");
        assertThat(e.eventId()).isNull();
        assertThat(e.normalized()).isNull();
        assertThat(e.intensity()).isNull();
        assertThat(e.phase()).isNull();
        assertThat(e.evidenceIds()).isEmpty();
        assertThat(e.hasNormalized()).isFalse();
    }
}
