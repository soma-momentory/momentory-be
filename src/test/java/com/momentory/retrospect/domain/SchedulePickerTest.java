package com.momentory.retrospect.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.momentory.retrospect.domain.Emotion;

class SchedulePickerTest {

    private static final ScheduleItem WORKOUT = new ScheduleItem("아침 운동", Emotion.PROUD);
    private static final ScheduleItem STUDY = new ScheduleItem("면접 스터디", Emotion.ANXIOUS);
    private static final ScheduleItem LECTURE = new ScheduleItem("취업 특강", Emotion.STUCK);

    @Test
    @DisplayName("일정이 하나면 무조건 그것")
    void singleSchedule() {
        assertThat(SchedulePicker.pick(List.of(STUDY), Emotion.DEPRESSED, null))
                .contains(STUDY);
    }

    @Test
    @DisplayName("현재 감정과 같은 태그가 정확히 하나면 그 일정")
    void emotionMatchWins() {
        assertThat(SchedulePicker.pick(List.of(WORKOUT, STUDY), Emotion.ANXIOUS, null))
                .contains(STUDY);
    }

    @Test
    @DisplayName("감정 매칭이 여러 개면 규칙으로 못 고른다 → empty")
    void ambiguousEmotionMatch() {
        ScheduleItem study2 = new ScheduleItem("영어 스터디", Emotion.ANXIOUS);
        assertThat(SchedulePicker.pick(List.of(STUDY, study2), Emotion.ANXIOUS, null))
                .isEmpty();
    }

    @Test
    @DisplayName("감정 매칭이 없으면 관심분야 키워드로 고른다 (공백·대소문자 무시)")
    void interestMatch() {
        assertThat(SchedulePicker.pick(List.of(WORKOUT, LECTURE), Emotion.DEPRESSED, "취업"))
                .contains(LECTURE);
        assertThat(SchedulePicker.pick(List.of(WORKOUT, LECTURE), Emotion.DEPRESSED, "취업 특강"))
                .contains(LECTURE);
    }

    @Test
    @DisplayName("아무 규칙도 못 고르면 empty — 엔진이 사용자에게 물어본다")
    void noMatchIsEmpty() {
        assertThat(SchedulePicker.pick(List.of(WORKOUT, STUDY), Emotion.DEPRESSED, "요리"))
                .isEmpty();
        assertThat(SchedulePicker.pick(List.of(WORKOUT, STUDY), Emotion.DEPRESSED, null))
                .isEmpty();
        assertThat(SchedulePicker.pick(List.of(), Emotion.DEPRESSED, null)).isEmpty();
    }
}
