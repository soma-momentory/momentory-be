package com.momentory.report.presentation;

import java.time.LocalDate;
import java.util.List;

import com.momentory.report.application.WeeklyReport;
import com.momentory.report.domain.WeeklyMood;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 주간 리포트 응답 — 「이번 주의 마음」과 「이번 주 한눈에」를 한 벌로 담는다.
 *
 * <p>{@code dailyMoods} 는 언제나 일곱 칸(일→토)이고, 기록이 없는 날은 {@code emotion} 이 null 이다.
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
        @Schema(description = "이번 주에 일기를 남긴 날 수 — 일기는 하루 한 벌이라 곧 일기 수다",
                example = "5") long diaryCount) {

    static WeeklyReportResponse from(WeeklyReport report) {
        WeeklyMood mood = report.mood();
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
                report.diaryCount());
    }
}
