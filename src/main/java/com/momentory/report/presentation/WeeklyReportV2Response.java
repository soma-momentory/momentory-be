package com.momentory.report.presentation;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.momentory.report.application.WeeklyReportV2;
import com.momentory.report.domain.DayStory;
import com.momentory.report.domain.WeeklyPages;
import com.momentory.retrospect.domain.Emotion;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 주간 리포트 v2 응답 — 표지와 안쪽 장의 내용을 한 벌로 담는다. 일기 본문은 싣지 않는다.
 * 재료가 없는 장은 비어서 온다: 화면이 그 장을 건너뛴다.
 */
public record WeeklyReportV2Response(
        @Schema(description = "주 시작일(일요일, KST)", example = "2026-10-25") LocalDate startDate,
        @Schema(description = "주 종료일(토요일, KST)", example = "2026-10-31") LocalDate endDate,
        @Schema(description = "가장 최근에 끝난 주인가") boolean newest,
        @Schema(description = "그 주에 일기를 남긴 날 수", example = "6") long diaryCount,
        @Schema(description = "안쪽 장이 열리는 주인가(일기 3일 이상)") boolean detailed,
        @Schema(description = "마지막 인사의 말투", allowableValues = {"NORMAL", "HARD", "CRISIS"}) String tone,
        @Schema(description = "위기 안내가 나온 주의 도움받을 곳 — 그 밖에는 빈 목록") List<String> helpResources,
        @Schema(description = "일요일부터 토요일까지 일곱 칸") List<Day> days,
        @Schema(description = "끝까지 한 쉼터 횟수", example = "4") long restCount,
        @Schema(description = "표지 문장") String coverLine,
        @Schema(description = "바바가 본 한 주 — 없으면 null") String review,
        @Schema(description = "바바가 본 한 주 끝의 좋은 말 — 없으면 null") String goodWords,
        @Schema(description = "괜찮았던 순간의 공통점 문장 — 없으면 null") String okLine,
        @Schema(description = "내가 남겼던 말 — 사용자가 직접 쓴 문장만") List<Word> words,
        @Schema(description = "감정과 있었던 일 — 많이 나온 순") List<EmotionEvents> emotions,
        @Schema(description = "괜찮았던 순간") List<OkMoment> okMoments,
        @Schema(description = "쉬어 간 곳") Rest rest,
        @Schema(description = "해낸 일") Done done) {

    public record Day(LocalDate date, boolean written,
            @Schema(description = "대표 감정 키 — 없으면 null", example = "calm") String emotion) {
    }

    public record Word(LocalDate date,
            @Schema(description = "문장 위에 붙는 이름 — 없으면 null", example = "편지에서 고른 산책 10분") String label,
            boolean fromLetter, String text) {
    }

    public record EmotionEvents(@Schema(example = "anxious") String emotion, long count, List<String> events) {
    }

    public record OkMoment(LocalDate date, @Schema(example = "calm") String emotion, String event, List<String> tasks,
            @Schema(description = "그날 「나아졌어요」를 고른 쉼터 — 없으면 null", example = "BODY_RELEASE")
            String betterRest) {
    }

    public record RestPlace(@Schema(example = "WORRY_BOX") String content, long total, long better, long same,
            long unsure) {
    }

    public record RestSoFar(@Schema(description = "null 이면 쉼터 전체를 합친 값") String content, long answered,
            long better) {
    }

    public record Rest(long total, long better, long same, long unsure, List<RestPlace> places, RestSoFar soFar) {
    }

    public record LinkedTask(LocalDate date, String title) {
    }

    public record Done(long completedCount,
            @Schema(description = "일요일부터 토요일까지 요일별 마친 수") List<Long> perDay, List<LinkedTask> linked) {
    }

    static WeeklyReportV2Response from(WeeklyReportV2 r) {
        Map<LocalDate, DayStory> byDate = r.stories().stream()
                .collect(Collectors.toMap(DayStory::date, Function.identity()));
        WeeklyPages.RestSummary rest = r.rest();
        return new WeeklyReportV2Response(r.startDate(), r.endDate(), r.newest(), r.diaryCount(), r.detailed(),
                r.tone().name(), r.helpResources(),
                java.util.stream.IntStream.range(0, 7).mapToObj(i -> r.startDate().plusDays(i)).map(date -> {
                    DayStory story = byDate.get(date);
                    return new Day(date, story != null, story == null ? null : key(story.emotion()));
                }).toList(),
                r.restCount(), r.coverLine(), r.review(), r.goodWords(), r.okLine(),
                r.words().stream().map(w -> new Word(w.date(), w.label(), w.fromLetter(), w.text())).toList(),
                r.emotions().stream().map(e -> new EmotionEvents(key(e.emotion()), e.count(), e.events())).toList(),
                r.okMoments().stream().map(m -> new OkMoment(m.date(), key(m.emotion()), m.event(), m.tasks(),
                        m.betterRest() == null ? null : m.betterRest().name())).toList(),
                new Rest(rest.total(), rest.better(), rest.same(), rest.unsure(),
                        rest.places().stream().map(p -> new RestPlace(p.content().name(), p.total(), p.better(),
                                p.same(), p.unsure())).toList(),
                        rest.soFar() == null ? null : new RestSoFar(
                                rest.soFar().content() == null ? null : rest.soFar().content().name(),
                                rest.soFar().answered(), rest.soFar().better())),
                new Done(r.done().completedCount(), r.done().perDay(),
                        r.done().linked().stream().map(t -> new LinkedTask(t.date(), t.title())).toList()));
    }

    private static String key(Emotion emotion) {
        return emotion == null ? null : emotion.key();
    }
}
