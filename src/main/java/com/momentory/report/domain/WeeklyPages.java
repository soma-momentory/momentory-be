package com.momentory.report.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.momentory.restrecord.domain.RestContent;
import com.momentory.restrecord.domain.RestMood;
import com.momentory.retrospect.domain.Emotion;

/**
 * 한 주의 재료를 장별 내용으로 정리한다 — 저장소도 AI 도 모르는 계산이라 여기서 다 시험한다.
 * 리포트는 관찰이므로 점수 · 비율 · 최고 기록을 만들지 않고, 횟수와 사용자가 고른 답만 다룬다.
 */
public final class WeeklyPages {

    /** 일정 한 줄 — 이 계산에 필요한 만큼만 받는다. */
    public record Task(Long id, LocalDate date, String title, boolean completed) {
    }

    /** 끝까지 한 쉼터 기록 한 건 — 기분을 고르지 않았으면 mood 가 null 이다. */
    public record Rest(RestContent content, LocalDate date, RestMood mood) {
    }

    public record Word(LocalDate date, String label, boolean fromLetter, String text) {
    }

    public record EmotionEvents(Emotion emotion, long count, List<String> events) {
    }

    public record OkMoment(LocalDate date, Emotion emotion, String event, List<String> tasks,
            RestContent betterRest) {
    }

    public record RestPlace(RestContent content, long total, long better, long same, long unsure) {
    }

    /** 쌓인 답 한 줄 — content 가 null 이면 쉼터 전체를 합친 값이다. */
    public record RestSoFar(RestContent content, long answered, long better) {
    }

    public record RestSummary(long total, long better, long same, long unsure, List<RestPlace> places,
            RestSoFar soFar) {
    }

    public record LinkedTask(LocalDate date, String title) {
    }

    public record DoneSummary(long completedCount, List<Long> perDay, List<LinkedTask> linked) {
    }

    private WeeklyPages() {
    }

    public static WeekTone tone(List<DayStory> stories) {
        if (stories.stream().anyMatch(DayStory::crisis)) {
            return WeekTone.CRISIS;
        }
        return stories.stream().anyMatch(s -> WeeklyReportRules.isGoodFeel(s.emotion())) ? WeekTone.NORMAL
                : WeekTone.HARD;
    }

    /**
     * 내가 남겼던 말 — 하루 한 줄씩, 편지에서 고른 일에 대한 답을 먼저, 그다음 최근 날부터 고른 뒤 날짜순으로
     * 둔다. 위기 안내가 나온 날의 말은 다시 꺼내지 않는다.
     */
    public static List<Word> words(List<DayStory> stories) {
        return stories.stream()
                .filter(s -> s.quote() != null && !s.crisis())
                .sorted(Comparator.comparing(DayStory::fromLetter).reversed()
                        .thenComparing(Comparator.comparing(DayStory::date).reversed()))
                .limit(WeeklyReportRules.WORDS_MAX)
                .sorted(Comparator.comparing(DayStory::date))
                .map(s -> new Word(s.date(), s.quoteLabel(), s.fromLetter(), s.quote()))
                .toList();
    }

    /** 감정과 있었던 일 — 일기마다 대표 감정 하나를 센다. 많이 나온 순이고, 같으면 먼저 나온 감정이 앞이다. */
    public static List<EmotionEvents> emotionEvents(List<DayStory> stories) {
        Map<Emotion, List<DayStory>> byEmotion = new LinkedHashMap<>();
        stories.stream().filter(s -> s.emotion() != null).sorted(Comparator.comparing(DayStory::date))
                .forEach(s -> byEmotion.computeIfAbsent(s.emotion(), e -> new ArrayList<>()).add(s));
        return byEmotion.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<Emotion, List<DayStory>> e) -> e.getValue().size())
                        .reversed())
                .map(e -> new EmotionEvents(e.getKey(), e.getValue().size(), distinctEvents(e.getValue())))
                .toList();
    }

    private static List<String> distinctEvents(List<DayStory> stories) {
        Set<String> events = new LinkedHashSet<>();
        stories.stream().map(DayStory::event).filter(Objects::nonNull).forEach(events::add);
        return events.stream().limit(WeeklyReportRules.EVENTS_PER_EMOTION_MAX).toList();
    }

    /**
     * 괜찮았던 순간 — 평온 · 뿌듯 · 행복을 고른 날만. 카드에는 그날 마친 일과 「나아졌어요」를 고른 쉼터를 적는다.
     * 카드 제목과 같은 일은 두 번 적지 않으려고 뺀다.
     */
    public static List<OkMoment> okMoments(List<DayStory> stories, List<Task> tasks, List<Rest> rests) {
        return stories.stream()
                .filter(s -> WeeklyReportRules.isGoodFeel(s.emotion()))
                .sorted(Comparator.comparing(DayStory::date))
                .map(s -> new OkMoment(s.date(), s.emotion(), s.event(), otherTasks(s, tasks),
                        rests.stream().filter(r -> s.date().equals(r.date()) && r.mood() == RestMood.BETTER)
                                .map(Rest::content).findFirst().orElse(null)))
                .toList();
    }

    private static List<String> otherTasks(DayStory story, List<Task> tasks) {
        return tasks.stream()
                .filter(t -> t.completed() && story.date().equals(t.date()) && !t.id().equals(story.scheduleId())
                        && !sameName(t.title(), story.event()))
                .map(Task::title)
                .limit(WeeklyReportRules.TASKS_MAX)
                .toList();
    }

    private static boolean sameName(String title, String event) {
        return event != null && (title.contains(event) || event.contains(title));
    }

    /**
     * 쉬어 간 곳 — 끝까지 한 기록만 센다. 많이 쓴 곳 순으로 전부 주고, 몇 곳을 줄로 보일지는 화면이 정한다.
     * {@code soFar} 는 이 주까지 쌓인 답이다: 답이 다섯 번 넘게 모인 쉼터 중 가장 많은 곳, 없으면 전체 합이다.
     */
    public static RestSummary rest(List<Rest> week, List<Rest> soFar) {
        Map<RestContent, List<Rest>> byContent = new LinkedHashMap<>();
        week.forEach(r -> byContent.computeIfAbsent(r.content(), c -> new ArrayList<>()).add(r));
        List<RestPlace> places = byContent.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<RestContent, List<Rest>> e) -> e.getValue().size())
                        .reversed())
                .map(e -> new RestPlace(e.getKey(), e.getValue().size(), count(e.getValue(), RestMood.BETTER),
                        count(e.getValue(), RestMood.SAME), count(e.getValue(), RestMood.UNSURE)))
                .toList();
        return new RestSummary(week.size(), count(week, RestMood.BETTER), count(week, RestMood.SAME),
                count(week, RestMood.UNSURE), places, restSoFar(soFar));
    }

    private static RestSoFar restSoFar(List<Rest> all) {
        List<Rest> answered = all.stream().filter(r -> r.mood() != null).toList();
        Map<RestContent, List<Rest>> byContent = new LinkedHashMap<>();
        answered.forEach(r -> byContent.computeIfAbsent(r.content(), c -> new ArrayList<>()).add(r));
        return byContent.entrySet().stream()
                .filter(e -> e.getValue().size() >= WeeklyReportRules.REST_SO_FAR_MIN)
                .max(Comparator.comparingInt(e -> e.getValue().size()))
                .map(e -> new RestSoFar(e.getKey(), e.getValue().size(), count(e.getValue(), RestMood.BETTER)))
                .orElseGet(() -> answered.size() >= WeeklyReportRules.REST_SO_FAR_MIN
                        ? new RestSoFar(null, answered.size(), count(answered, RestMood.BETTER))
                        : null);
    }

    private static long count(List<Rest> rests, RestMood mood) {
        return rests.stream().filter(r -> r.mood() == mood).count();
    }

    /** 해낸 일 — 그 주에 잡힌 일정 중 마친 수와 요일별 수. 일기에도 남은 일은 회고가 고른 일정이다. */
    public static DoneSummary done(LocalDate weekStart, List<Task> tasks, List<DayStory> stories) {
        List<Task> completed = tasks.stream().filter(Task::completed).toList();
        List<Long> perDay = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate day = weekStart.plusDays(i);
            perDay.add(completed.stream().filter(t -> day.equals(t.date())).count());
        }
        Set<Long> scheduleIds = new LinkedHashSet<>();
        stories.stream().map(DayStory::scheduleId).filter(Objects::nonNull).forEach(scheduleIds::add);
        List<LinkedTask> linked = completed.stream().filter(t -> scheduleIds.contains(t.id()))
                .sorted(Comparator.comparing(Task::date))
                .map(t -> new LinkedTask(t.date(), t.title()))
                .toList();
        return new DoneSummary(completed.size(), List.copyOf(perDay), linked);
    }
}
