package com.momentory.report.application;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import org.springframework.stereotype.Service;

import com.momentory.common.time.DayBoundary;
import com.momentory.report.domain.WeekTone;
import com.momentory.report.domain.WeeklyPages;
import com.momentory.report.domain.WeeklyReportRules;
import com.momentory.retrospect.domain.safety.SafetyPolicy;

/**
 * 주간 리포트 v2 조회 유스케이스 — 끝난 한 주를 공책 한 권으로 돌려준다.
 *
 * <p>날짜를 주지 않으면 일기가 남은 가장 최근의 끝난 주를 연다. 주는 일요일 04:00(KST)에 바뀐다.
 * 사실은 열 때마다 세고, AI 글만 저장한다. AI 호출이 끼므로 이 메서드 전체를 트랜잭션으로 묶지 않는다.
 */
@Service
public class WeeklyReportV2Service {

    private final WeeklyMaterialsReader reader;
    private final WeeklyWritingService writings;
    private final SafetyPolicy safetyPolicy;

    WeeklyReportV2Service(WeeklyMaterialsReader reader, WeeklyWritingService writings, SafetyPolicy safetyPolicy) {
        this.reader = reader;
        this.writings = writings;
        this.safetyPolicy = safetyPolicy;
    }

    public WeeklyReportV2 getWeekly(Long userId, LocalDate date) {
        LocalDate lastWeek = sundayOf(DayBoundary.today()).minusDays(7);
        LocalDate start = date == null ? reader.latestWrittenWeek(userId, lastWeek) : sundayOf(date);
        WeeklyMaterials m = reader.read(userId, start);
        WeeklyWriting writing = writings.ensure(m);
        WeekTone tone = WeeklyPages.tone(m.stories());
        return new WeeklyReportV2(start, m.weekEnd(), start.equals(lastWeek), m.stories().size(),
                m.stories().size() >= WeeklyReportRules.DETAILED_DIARY_COUNT, tone,
                tone == WeekTone.CRISIS ? safetyPolicy.resources() : List.of(), m.stories(),
                m.rests().size(), writing.coverLine(), writing.review(), writing.goodWords(), writing.okLine(),
                WeeklyPages.words(m.stories()), WeeklyPages.emotionEvents(m.stories()),
                WeeklyPages.okMoments(m.stories(), m.tasks(), m.rests()), WeeklyPages.rest(m.rests(), m.restsSoFar()),
                WeeklyPages.done(start, m.tasks(), m.stories()));
    }

    private static LocalDate sundayOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
    }
}
