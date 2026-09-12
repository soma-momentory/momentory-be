package com.momentory.report.presentation;

import java.time.LocalDate;
import java.util.List;

import com.momentory.report.application.WeeklyReport;
import com.momentory.report.domain.WeeklyMood;
import com.momentory.report.domain.WeeklyWishes;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 주간 리포트 응답 — 한 주의 마음 · 바람카드 · 셈을 한 벌로 담는다.
 *
 * <p>{@code dailyMoods} 는 언제나 일곱 칸(일→토)이고, 기록이 없는 날은 {@code emotion} 이 null 이다.
 *
 * <p>{@code needs} 와 {@code practicedWishes} 는 <b>그 주에 만들어진 바람 카드</b>에서 나온다
 * ({@link WeeklyWishes}) — {@code actionCardCreatedCount} 와 같은 기준이라 셈과 목록이 어긋나지
 * 않는다. 카드가 없으면 둘 다 빈 배열이다(null 이 아니다).
 */
public record WeeklyReportResponse(
        @Schema(description = "주 시작일(일요일, KST)", example = "2026-08-16") LocalDate startDate,
        @Schema(description = "주 종료일(토요일, KST)", example = "2026-08-22") LocalDate endDate,
        @Schema(description = "일요일부터 토요일까지 일곱 칸의 마음") List<DailyMoodResponse> dailyMoods,
        @Schema(description = "가장 자주 느낀 감정 키 — 최다가 여럿이거나 기록이 없으면 null",
                example = "calm") String dominantEmotion,
        @Schema(description = "마음 요약 멘트 — 가장 많이 느낀 감정에 따라 정해진 문장",
                example = "이번 주에는 평온한 마음을 가장 많이 느꼈어요. 나를 편안하게 해준 환경이나 행동을 다음 주에도 이어가 보세요.")
        String moodMessage,
        @Schema(description = "이번 주 일정 수(숨김·삭제 제외)", example = "12") long scheduleTotalCount,
        @Schema(description = "그중 완료된 일정 수", example = "9") long scheduleCompletedCount,
        @Schema(description = "이번 주에 만들어진 행동 카드 수", example = "5") long actionCardCreatedCount,
        @Schema(description = "그중 실천(해봤어요)한 행동 카드 수", example = "3")
        long actionCardCompletedCount,
        @Schema(description = "이번 주에 찾은 바람 — 같은 단어는 합치고 몇 번 나왔는지 센다(많이 나온 순)")
        List<NeedCountResponse> needs,
        @Schema(description = "이번 주에 실천한 바람 — 최신순. 길이는 actionCardCompletedCount 와 같다")
        List<PracticedWishResponse> practicedWishes,
        @Schema(description = "이번 주에 일기를 남긴 날 수 — 일기는 하루 한 벌이라 곧 일기 수다",
                example = "5") long diaryCount) {

    /** 그 주에 찾은 바람 하나 — 「휴식 ×2」처럼 몇 번 나왔는지까지 화면이 적는다. */
    public record NeedCountResponse(
            @Schema(description = "바람 단어", example = "휴식") String word,
            @Schema(description = "그 주에 이 바람이 나온 카드 수", example = "2") long count) {

        static NeedCountResponse from(WeeklyWishes.NeedCount need) {
            return new NeedCountResponse(need.word(), need.count());
        }
    }

    /** 실천한 바람 하나 — 작은 행동을 정하지 않고 해본 카드는 {@code action} 이 null 이다. */
    public record PracticedWishResponse(
            @Schema(description = "실천한 작은 행동 — 정하지 않았으면 null",
                    example = "회의가 끝난 뒤 느낀 점을 한 문장으로 전해보기") String action,
            @Schema(description = "그 카드의 바람 단어", example = "[\"휴식\"]") List<String> needs) {

        static PracticedWishResponse from(WeeklyWishes.PracticedWish wish) {
            return new PracticedWishResponse(wish.action(), wish.needs());
        }
    }

    static WeeklyReportResponse from(WeeklyReport report) {
        WeeklyMood mood = report.mood();
        WeeklyWishes wishes = report.wishes();
        return new WeeklyReportResponse(
                report.startDate(),
                report.endDate(),
                mood.days().stream().map(DailyMoodResponse::from).toList(),
                mood.dominantEmotion() == null ? null : mood.dominantEmotion().key(),
                mood.message(),
                report.scheduleTotalCount(),
                report.scheduleCompletedCount(),
                report.actionCardCreatedCount(),
                report.actionCardCompletedCount(),
                wishes.needs().stream().map(NeedCountResponse::from).toList(),
                wishes.practiced().stream().map(PracticedWishResponse::from).toList(),
                report.diaryCount());
    }
}
