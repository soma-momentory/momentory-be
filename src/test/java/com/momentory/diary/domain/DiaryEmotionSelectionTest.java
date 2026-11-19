package com.momentory.diary.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.momentory.retrospect.domain.Emotion;

class DiaryEmotionSelectionTest {
    private DiaryEmotionSelection selection(List<Emotion> first, List<Emotion> second, List<Emotion> unlinked) {
        return new DiaryEmotionSelection(List.of(
                new DiaryEmotionSelection.Event(1, "만남", "친구를 만났다", first),
                new DiaryEmotionSelection.Event(2, "산책", "공원을 걸었다", second)), unlinked);
    }

    @Test void additionsDoNotCountAsCorrection() {
        var original = selection(List.of(Emotion.JOY), List.of(), List.of(Emotion.SAD));
        assertThat(selection(List.of(Emotion.JOY, Emotion.CALM), List.of(Emotion.PROUD),
                List.of(Emotion.SAD, Emotion.ANXIOUS)).removesFrom(original)).isFalse();
    }

    @Test void anOriginalEmotionRemainingOnAnotherEventDoesNotCount() {
        var original = selection(List.of(Emotion.JOY), List.of(Emotion.JOY), List.of());
        assertThat(selection(List.of(Emotion.SAD), List.of(Emotion.JOY), List.of())
                .removesFrom(original)).isFalse();
    }

    @Test void removingOnlyUserAddedEmotionDoesNotCount() {
        var original = selection(List.of(Emotion.JOY), List.of(), List.of());
        assertThat(selection(List.of(Emotion.JOY), List.of(), List.of()).removesFrom(original)).isFalse();
    }

    @Test void removingUnlinkedOriginalEmotionCounts() {
        var original = selection(List.of(), List.of(), List.of(Emotion.SAD));
        assertThat(selection(List.of(), List.of(), List.of()).removesFrom(original)).isTrue();
    }

    @Test void movingAnOriginalEmotionBetweenAnEventAndUnlinkedDoesNotCount() {
        var original = selection(List.of(Emotion.JOY), List.of(), List.of(Emotion.SAD));
        assertThat(selection(List.of(Emotion.SAD), List.of(), List.of(Emotion.JOY)).removesFrom(original)).isFalse();
    }

    @Test void anyMissingOriginalEmotionCountsEvenIfOthersAndAdditionsRemain() {
        var original = selection(List.of(Emotion.JOY, Emotion.CALM), List.of(), List.of(Emotion.SAD));
        assertThat(selection(List.of(Emotion.JOY, Emotion.PROUD), List.of(), List.of(Emotion.SAD)).removesFrom(original)).isTrue();
        assertThat(selection(List.of(Emotion.JOY, Emotion.CALM), List.of(), List.of(Emotion.SAD, Emotion.PROUD)).removesFrom(original)).isFalse();
    }
}
