package com.momentory.report.application;

import java.time.LocalDate;

import com.momentory.report.domain.WeeklyMood;

/**
 * 한 주(일~토, KST)치 리포트 한 벌 — 「이번 주의 마음」({@link WeeklyMood})과 「이번 주 한눈에」의 셈.
 *
 * <p>{@code actionCardCompletedCount} 는 <b>이 주에 만들어진</b> 카드 중 완료된 수다(지난주에 만든
 * 카드를 이번 주에 해봤다면 여기 잡히지 않는다). 일정도 같은 결로, 이 주에 잡힌 일정 중 완료된 수다.
 */
public record WeeklyReport(
        LocalDate startDate,
        LocalDate endDate,
        WeeklyMood mood,
        long scheduleTotalCount,
        long scheduleCompletedCount,
        long actionCardCreatedCount,
        long actionCardCompletedCount,
        long diaryCount) {
}
