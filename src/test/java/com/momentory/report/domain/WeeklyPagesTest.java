package com.momentory.report.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.momentory.restrecord.domain.RestContent;
import com.momentory.restrecord.domain.RestMood;
import com.momentory.retrospect.domain.Emotion;

class WeeklyPagesTest {

    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 25);

    private static DayStory story(int day, Emotion emotion, String event, String quote) {
        return new DayStory((long) day, SUNDAY.plusDays(day), emotion, event, quote, event, false, false, null, "일기",
                List.of());
    }

    @Test
    void toneIsCrisisFirstThenHardWithoutGoodFeel() {
        DayStory crisis = new DayStory(1L, SUNDAY, Emotion.CALM, null, null, null, false, true, null, "일기", List.of());
        assertThat(WeeklyPages.tone(List.of(crisis))).isEqualTo(WeekTone.CRISIS);
        assertThat(WeeklyPages.tone(List.of(story(0, Emotion.EXHAUSTED, "야근", null)))).isEqualTo(WeekTone.HARD);
        assertThat(WeeklyPages.tone(List.of(story(0, Emotion.EXHAUSTED, "야근", null), story(1, Emotion.CALM, "산책", null))))
                .isEqualTo(WeekTone.NORMAL);
    }

    @Test
    void wordsPreferLetterAnswersThenRecentAndSkipCrisisDays() {
        DayStory letter = new DayStory(1L, SUNDAY, Emotion.CALM, null, "편지에서 고른 일을 해 봤어요", "편지에서 고른 산책", true,
                false, null, "일기", List.of());
        DayStory crisis = new DayStory(2L, SUNDAY.plusDays(6), Emotion.EXHAUSTED, null, "위기 안내가 나온 날의 말", null, false,
                true, null, "일기", List.of());
        List<WeeklyPages.Word> words = WeeklyPages.words(List.of(letter, story(1, Emotion.CALM, "ㄱ", "첫째 날 문장입니다"),
                story(2, Emotion.CALM, "ㄴ", "둘째 날 문장입니다"), story(3, Emotion.CALM, "ㄷ", "셋째 날 문장입니다"), crisis));

        assertThat(words).extracting(WeeklyPages.Word::text)
                .containsExactly("편지에서 고른 일을 해 봤어요", "둘째 날 문장입니다", "셋째 날 문장입니다");
    }

    @Test
    void emotionEventsCountPrimaryEmotionAndKeepDistinctEvents() {
        List<WeeklyPages.EmotionEvents> rows = WeeklyPages.emotionEvents(List.of(story(0, Emotion.CALM, "친구와 저녁", null),
                story(1, Emotion.ANXIOUS, "면접 스터디", null), story(2, Emotion.ANXIOUS, "면접 스터디", null),
                story(3, Emotion.ANXIOUS, "발표", null), story(4, null, "감정 없는 날", null)));

        assertThat(rows.getFirst().emotion()).isEqualTo(Emotion.ANXIOUS);
        assertThat(rows.getFirst().count()).isEqualTo(3);
        assertThat(rows.getFirst().events()).containsExactly("면접 스터디", "발표");
        assertThat(rows).hasSize(2);
    }

    @Test
    void okMomentsListOtherFinishedTasksAndBetterRest() {
        List<WeeklyPages.Task> tasks = List.of(
                new WeeklyPages.Task(1L, SUNDAY, "장보기", true),
                new WeeklyPages.Task(2L, SUNDAY, "친구와 저녁 약속", true),
                new WeeklyPages.Task(3L, SUNDAY, "빨래", false));
        List<WeeklyPages.Rest> rests = List.of(new WeeklyPages.Rest(RestContent.WORRY_BOX, SUNDAY, RestMood.SAME),
                new WeeklyPages.Rest(RestContent.BODY_RELEASE, SUNDAY, RestMood.BETTER));

        List<WeeklyPages.OkMoment> moments = WeeklyPages.okMoments(
                List.of(story(0, Emotion.CALM, "친구와 저녁", null), story(1, Emotion.EXHAUSTED, "야근", null)), tasks, rests);

        assertThat(moments).hasSize(1);
        assertThat(moments.getFirst().tasks()).containsExactly("장보기");
        assertThat(moments.getFirst().betterRest()).isEqualTo(RestContent.BODY_RELEASE);
    }

    @Test
    void restSoFarUsesThePlaceWithFiveAnswersElseTheTotal() {
        WeeklyPages.Rest worry = new WeeklyPages.Rest(RestContent.WORRY_BOX, SUNDAY, RestMood.BETTER);
        WeeklyPages.Rest pond = new WeeklyPages.Rest(RestContent.SENSE_POND, SUNDAY, RestMood.SAME);
        WeeklyPages.Rest noAnswer = new WeeklyPages.Rest(RestContent.SENSE_POND, SUNDAY, null);

        assertThat(WeeklyPages.rest(List.of(worry), List.of(worry, worry, worry, pond)).soFar()).isNull();
        assertThat(WeeklyPages.rest(List.of(worry), List.of(worry, worry, worry, pond, pond, noAnswer)).soFar())
                .isEqualTo(new WeeklyPages.RestSoFar(null, 5, 3));
        WeeklyPages.RestSummary summary = WeeklyPages.rest(List.of(worry, noAnswer),
                List.of(worry, worry, worry, worry, worry, pond));
        assertThat(summary.soFar()).isEqualTo(new WeeklyPages.RestSoFar(RestContent.WORRY_BOX, 5, 5));
        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.better()).isEqualTo(1);
    }

    @Test
    void doneCountsPerDayAndLinksOnlyTheRetrospectSchedule() {
        DayStory linked = new DayStory(1L, SUNDAY, Emotion.CALM, null, null, null, false, false, 2L, "일기", List.of());
        WeeklyPages.DoneSummary done = WeeklyPages.done(SUNDAY, List.of(
                new WeeklyPages.Task(1L, SUNDAY, "장보기", true),
                new WeeklyPages.Task(2L, SUNDAY.plusDays(2), "발표", true),
                new WeeklyPages.Task(3L, SUNDAY.plusDays(2), "빨래", false)), List.of(linked));

        assertThat(done.completedCount()).isEqualTo(2);
        assertThat(done.perDay()).containsExactly(1L, 0L, 1L, 0L, 0L, 0L, 0L);
        assertThat(done.linked()).containsExactly(new WeeklyPages.LinkedTask(SUNDAY.plusDays(2), "발표"));
    }

    @Test
    void rulesDropBannedWordsAndWrongOkLineForm() {
        assertThat(WeeklyReportRules.checked("정말 대단해요", 100)).isNull();
        assertThat(WeeklyReportRules.checked("글자가 너무 길어요", 3)).isNull();
        assertThat(WeeklyReportRules.checkedOkLine("친구를 만난 날 괜찮았어요.")).isEqualTo("친구를 만난 날 괜찮았어요.");
        assertThat(WeeklyReportRules.checkedOkLine("친구를 만난 날이 좋았어요")).isNull();
    }
}
