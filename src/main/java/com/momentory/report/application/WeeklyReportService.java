package com.momentory.report.application;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.momentory.actioncard.application.ActionCardQueryService;
import com.momentory.actioncard.application.ActionCardView;
import com.momentory.common.time.DayBoundary;
import com.momentory.diary.application.DiaryQueryService;
import com.momentory.report.domain.DailyMood;
import com.momentory.report.domain.WeeklyMood;
import com.momentory.report.domain.WeeklyWishes;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.schedule.application.ScheduleResult;
import com.momentory.schedule.application.ScheduleService;

/**
 * 주간 리포트 조회 유스케이스 — 한 주(일~토, KST)의 마음·일정·행동 카드·일기를 한 벌로 모은다.
 *
 * <p>리포트는 <b>세는</b> 화면이라 제 테이블을 갖지 않는다. 각 도메인의 조회 유스케이스에서 필요한
 * 만큼만 받아 합칠 뿐이라, 일정의 숨김·삭제 규칙이나 일기의 하루 경계 같은 판단은 그 도메인에 그대로
 * 남는다.
 */
@Service
public class WeeklyReportService {

    private static final int WEEK_LENGTH = 7;

    private final DiaryQueryService diaryQueryService;
    private final ScheduleService scheduleService;
    private final ActionCardQueryService actionCardQueryService;

    public WeeklyReportService(DiaryQueryService diaryQueryService, ScheduleService scheduleService,
            ActionCardQueryService actionCardQueryService) {
        this.diaryQueryService = diaryQueryService;
        this.scheduleService = scheduleService;
        this.actionCardQueryService = actionCardQueryService;
    }

    /**
     * {@code date} 가 속한 주(일~토, KST)의 리포트. {@code date} 가 null 이면 오늘(KST · 04:00 하루
     * 경계)이 속한 주 — 클라이언트가 주 이동 없이 처음 열 때다.
     */
    @Transactional(readOnly = true)
    public WeeklyReport getWeekly(Long userId, LocalDate date) {
        LocalDate target = date == null ? DayBoundary.today() : date;
        LocalDate startDate = target.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        LocalDate endDate = startDate.plusDays(WEEK_LENGTH - 1L);

        Map<LocalDate, List<Emotion>> emotionsByDate =
                diaryQueryService.getDailyEmotions(userId, startDate, endDate);
        WeeklyMood mood = WeeklyMood.of(IntStream.range(0, WEEK_LENGTH)
                .mapToObj(offset -> startDate.plusDays(offset))
                .map(day -> DailyMood.of(day, emotionsByDate.get(day)))
                .toList());

        // 일정은 목록 화면과 같은 규칙(숨김·삭제 제외)으로 세야 어긋나지 않아 조회 유스케이스를 그대로
        // 쓴다. 이레짜리 구간이라 기간 검증(최대 366일)에는 걸리지 않는다.
        List<ScheduleResult> schedules =
                scheduleService.getSchedulesInPeriod(userId, startDate, endDate);
        long scheduleCompletedCount = schedules.stream().filter(ScheduleResult::completed).count();

        // 바람 카드는 **목록으로** 받는다 — 화면이 「이번 주에 찾은 바람」과 「실천한 것」을 적으므로
        // 수만으로는 모자란다. 셈도 이 목록에서 나온다(수와 목록이 다른 조회에서 오면 어긋난다).
        List<ActionCardView> actionCards =
                actionCardQueryService.findInPeriod(userId, startDate, endDate);
        long actionCardCompletedCount = actionCards.stream().filter(ActionCardView::done).count();
        WeeklyWishes wishes = WeeklyWishes.of(actionCards.stream()
                .map(card -> new WeeklyWishes.WishCard(card.needWords(), card.targetAction(),
                        card.done()))
                .toList());

        return new WeeklyReport(startDate, endDate, mood, wishes,
                schedules.size(), scheduleCompletedCount,
                actionCards.size(), actionCardCompletedCount,
                emotionsByDate.size());
    }
}
