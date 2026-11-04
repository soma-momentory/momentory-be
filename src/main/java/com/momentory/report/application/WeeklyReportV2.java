package com.momentory.report.application;

import java.time.LocalDate;
import java.util.List;

import com.momentory.report.domain.DayStory;
import com.momentory.report.domain.WeekTone;
import com.momentory.report.domain.WeeklyPages;

/**
 * 주간 리포트 v2 한 벌 — 공책 표지와 안쪽 장의 내용이다.
 *
 * @param newest   가장 최근에 끝난 주인가 — 화면이 「새 공책」 표시를 이 값으로 정한다
 * @param detailed 안쪽 장이 열리는 주인가(일기 3일 이상)
 * @param helpResources 위기 안내가 나온 주에만 채우는 도움받을 곳 목록 — 회고 · 편지와 같은 목록이다
 */
public record WeeklyReportV2(LocalDate startDate, LocalDate endDate, boolean newest, long diaryCount,
        boolean detailed, WeekTone tone, List<String> helpResources, List<DayStory> stories, long restCount, String coverLine, String review,
        String goodWords, String okLine, List<WeeklyPages.Word> words, List<WeeklyPages.EmotionEvents> emotions,
        List<WeeklyPages.OkMoment> okMoments, WeeklyPages.RestSummary rest, WeeklyPages.DoneSummary done) {
}
