package com.momentory.report.application;

import java.time.LocalDate;

import com.momentory.report.domain.WeeklyMood;
import com.momentory.report.domain.WeeklyWishes;

/**
 * 한 주(일~토, KST)치 리포트 한 벌 — 「이번 주 한눈에」({@link WeeklyMood}) · 「이번 주 바람카드」
 * ({@link WeeklyWishes}) · 화면이 한 줄씩 적는 셈.
 *
 * <p>{@code actionCardCompletedCount} 는 <b>이 주에 만들어진</b> 카드 중 완료된 수다(지난주에 만든
 * 카드를 이번 주에 해봤다면 여기 잡히지 않는다). 일정도 같은 결로, 이 주에 잡힌 일정 중 완료된 수다.
 */
public record WeeklyReport(
        LocalDate startDate,
        LocalDate endDate,
        WeeklyMood mood,
        WeeklyWishes wishes,
        long scheduleTotalCount,
        long scheduleCompletedCount,
        long actionCardCreatedCount,
        long actionCardCompletedCount,
        long diaryCount) {
}
