package com.momentory.report.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.momentory.retrospect.domain.Emotion;

/** 「이번 주의 마음」 멘트 규칙 — AI 없이 고정 규칙으로만 만든다. */
class WeeklyMoodTest {

    private static final LocalDate WEEK_START = LocalDate.of(2026, 8, 16);
    private static final String MIXED_MESSAGE = "여러 마음이 번갈아 찾아온 한 주였어요.";

    @Test
    @DisplayName("최다 감정이 하나면 그 감정을 넣어 멘트를 만든다")
    void singleDominantEmotionShapesMessage() {
        WeeklyMood mood = WeeklyMood.of(week(
                Emotion.DEPRESSED, Emotion.HAPPY, Emotion.CALM, null, Emotion.CALM, null, null));

        assertThat(mood.dominantEmotion()).isEqualTo(Emotion.CALM);
        assertThat(mood.message()).isEqualTo("이번 주에는 평온한 마음을 가장 많이 느꼈어요. "
                + "나를 편안하게 해준 환경이나 행동을 다음 주에도 이어가 보세요.");
    }

    @Test
    @DisplayName("하루만 기록해도 그 감정이 최다다")
    void singleRecordedDayIsDominant() {
        WeeklyMood mood = WeeklyMood.of(week(null, null, Emotion.PROUD, null, null, null, null));

        assertThat(mood.dominantEmotion()).isEqualTo(Emotion.PROUD);
        assertThat(mood.message()).isEqualTo("이번 주에는 뿌듯한 마음을 가장 많이 느꼈어요. "
                + "노력한 만큼 스스로의 변화를 발견한 한 주였을지도 몰라요.");
    }

    @Test
    @DisplayName("최다 횟수를 여러 감정이 나눠 가지면 '여러 마음' 멘트로 바뀐다")
    void tiedEmotionsFallBackToMixedMessage() {
        WeeklyMood mood = WeeklyMood.of(week(
                Emotion.CALM, Emotion.CALM, Emotion.TIRED, Emotion.TIRED, Emotion.HAPPY, null, null));

        assertThat(mood.dominantEmotion()).isNull();
        assertThat(mood.message()).isEqualTo(MIXED_MESSAGE);
    }

    @Test
    @DisplayName("서로 다른 감정이 한 번씩이어도 최다가 여럿이라 '여러 마음' 멘트다")
    void allDistinctEmotionsAreTied() {
        WeeklyMood mood = WeeklyMood.of(week(
                Emotion.ANXIOUS, Emotion.ANGRY, null, null, null, null, null));

        assertThat(mood.dominantEmotion()).isNull();
        assertThat(mood.message()).isEqualTo(MIXED_MESSAGE);
    }

    @Test
    @DisplayName("한 칸도 기록되지 않은 주는 빈 주 멘트를 낸다")
    void emptyWeekHasItsOwnMessage() {
        WeeklyMood mood = WeeklyMood.of(week(null, null, null, null, null, null, null));

        assertThat(mood.dominantEmotion()).isNull();
        assertThat(mood.message()).isEqualTo("아직 이번 주에 기록된 마음이 없어요.");
    }

    @Test
    @DisplayName("감정 열 종 모두 저마다의 멘트를 갖는다 — 빈 문장도, 겹치는 문장도 없다")
    void everyEmotionHasItsOwnMessage() {
        Set<String> messages = new LinkedHashSet<>();
        for (Emotion emotion : Emotion.values()) {
            WeeklyMood mood = WeeklyMood.of(week(emotion, null, null, null, null, null, null));
            assertThat(mood.message())
                    .as("%s 의 멘트", emotion.key())
                    .isNotBlank()
                    .startsWith("이번 주에는 ");
            assertThat(mood.message()).isNotEqualTo(MIXED_MESSAGE);
            messages.add(mood.message());
        }
        assertThat(messages).hasSize(Emotion.values().length);
    }

    @Test
    @DisplayName("우울함·피곤함은 어근을 붙인 말이 아니라 정해둔 표현을 쓴다")
    void messagesUseTheirOwnWording() {
        assertThat(WeeklyMood.of(week(Emotion.DEPRESSED, null, null, null, null, null, null))
                .message())
                .isEqualTo("이번 주에는 가라앉은 마음을 가장 많이 느꼈어요. "
                        + "마음을 무겁게 만든 일이 무엇이었는지 천천히 살펴보세요.");
        assertThat(WeeklyMood.of(week(Emotion.TIRED, null, null, null, null, null, null))
                .message())
                .isEqualTo("이번 주에는 피곤함을 가장 많이 느꼈어요. "
                        + "몸과 마음이 보내는 휴식 신호를 지나치고 있지는 않은지 살펴보세요.");
        assertThat(WeeklyMood.of(week(Emotion.ANGRY, null, null, null, null, null, null))
                .message())
                .isEqualTo("이번 주에는 화난 마음을 가장 많이 느꼈어요. "
                        + "원하는 대로 되지 않거나 참아야 했던 일이 있었을지도 몰라요.");
    }

    @Test
    @DisplayName("일곱 칸은 그대로 지키고, 밖에서 바꿀 수 없다")
    void daysAreKeptInOrderAndUnmodifiable() {
        List<DailyMood> days = new ArrayList<>(week(
                Emotion.CALM, null, null, null, null, null, null));
        WeeklyMood mood = WeeklyMood.of(days);
        days.clear();

        assertThat(mood.days()).hasSize(7);
        assertThat(mood.days().getFirst().date()).isEqualTo(WEEK_START);
        assertThat(mood.days().get(6).date()).isEqualTo(WEEK_START.plusDays(6));
        assertThatThrownBy(() -> mood.days().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("하루에 감정이 여럿이면 그 감정들이 각각 한 표씩 셈에 들어간다")
    void everyEmotionOfADayCounts() {
        // 「늦게 일어나서 기분 안좋았는데 라면 먹고 기분 좋아졌어」 — 대표 감정만 세면 행복함은
        // 한 주 요약에 한 번도 안 잡힌다. 이 주는 행복함 2 · 우울함 1 · 평온함 1 이라 행복함이다.
        WeeklyMood mood = WeeklyMood.of(List.of(
                DailyMood.of(WEEK_START, List.of(Emotion.DEPRESSED, Emotion.HAPPY)),
                DailyMood.of(WEEK_START.plusDays(1), List.of(Emotion.CALM, Emotion.HAPPY))));

        assertThat(mood.dominantEmotion()).isEqualTo(Emotion.HAPPY);
    }

    @Test
    @DisplayName("여러 감정을 세다 최다가 갈리면 '여러 마음' 멘트로 간다")
    void tieAcrossAllEmotionsStillMixes() {
        // 우울함 1 · 행복함 1 — 대표 감정만 셌다면 우울함 하나가 이겼을 자리다.
        WeeklyMood mood = WeeklyMood.of(List.of(
                DailyMood.of(WEEK_START, List.of(Emotion.DEPRESSED, Emotion.HAPPY))));

        assertThat(mood.dominantEmotion()).isNull();
        assertThat(mood.message()).isEqualTo("여러 마음이 번갈아 찾아온 한 주였어요.");
    }

    private static List<DailyMood> week(Emotion... emotions) {
        List<Emotion> values = Arrays.asList(emotions);
        return java.util.stream.IntStream.range(0, values.size())
                .mapToObj(offset -> DailyMood.of(WEEK_START.plusDays(offset),
                        values.get(offset) == null ? List.of() : List.of(values.get(offset))))
                .toList();
    }
}
