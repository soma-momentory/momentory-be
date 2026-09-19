package com.momentory.actioncard.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.momentory.common.time.DayBoundary;
import com.momentory.actioncard.infrastructure.persistence.ActionCardRepository;

/**
 * 행동 카드 조회 유스케이스 — 보관함의 월별 목록과 단건 조회. 카드 생성은 회고 완료 흐름
 * ({@link RetrospectService}) 이 맡고, 여기선 읽기만 한다. 일기 월별 조회
 * ({@link DiaryQueryService}) 와 같은 {@code created_at} KST 월 경계를 쓴다.
 */
@Service
public class ActionCardQueryService {

    private final ActionCardRepository actionCardRepository;

    public ActionCardQueryService(ActionCardRepository actionCardRepository) {
        this.actionCardRepository = actionCardRepository;
    }

    /**
     * 한 달치 행동 카드(최신순). 월 경계는 KST 04:00 하루 경계로 잡아 {@code [해당 월 1일 04:00,
     * 다음 달 1일 04:00)} 반열림 구간으로 조회한다({@link DayBoundary} · 일기와 같은 경계).
     *
     * @throws java.time.DateTimeException 월이 1~12 범위를 벗어나면(표현 계층이 400 으로 번역)
     */
    @Transactional(readOnly = true)
    public List<ActionCardView> getMonthly(Long userId, int year, int month) {
        YearMonth target = YearMonth.of(year, month);
        Instant start = DayBoundary.startOfDay(target.atDay(1));
        Instant end = DayBoundary.startOfDay(target.plusMonths(1).atDay(1));
        return actionCardRepository
                .findByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                        userId, start, end)
                .stream()
                .map(ActionCardView::from)
                .toList();
    }

    /**
     * {@code [from, to]}(KST, 양끝 포함) 안에 만들어진 행동 카드(최신순) — 주간 리포트가 쓴다.
     * 보관함 월별 조회와 같은 {@code created_at} 기준이라, 목록에 보이는 카드와 리포트가 말하는
     * 카드가 어긋나지 않는다.
     *
     * <p>세기만 하던 자리였는데(옛 {@code countInPeriod}) 리포트가 「이번 주에 찾은 바람」과
     * 「실천한 것」까지 말하게 되면서 카드 자체가 필요해졌다. 셈도 이 목록에서 나온다 — 수와
     * 목록을 다른 조회에서 얻으면 둘이 서로 어긋날 자리가 생긴다(한 주는 카드 몇 장이라
     * 목록으로 받아도 무겁지 않다).
     */
    @Transactional(readOnly = true)
    public List<ActionCardView> findInPeriod(Long userId, LocalDate from, LocalDate to) {
        Instant start = DayBoundary.startOfDay(from);
        Instant end = DayBoundary.startOfDay(to.plusDays(1));
        return actionCardRepository
                .findByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                        userId, start, end)
                .stream()
                .map(ActionCardView::from)
                .toList();
    }

    /** 행동 카드 단건 — 소유권을 함께 검증한다. */
    @Transactional(readOnly = true)
    public ActionCardView getOne(Long userId, Long id) {
        return actionCardRepository.findByIdAndUserId(id, userId)
                .map(ActionCardView::from)
                .orElseThrow(ActionCardNotFoundException::new);
    }

    /**
     * 그 회고가 남긴 행동 카드의 id — 회고 완료 직후 서비스가 응답에 실어 보낼 때 쓴다(방금 저장된
     * 카드를 찾는다). 회고 한 벌에 카드 하나라 유일하다. 없으면(카드 없는 방향·저장 실패) 빈 값.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Long> findIdByRetrospect(Long retrospectId) {
        return actionCardRepository.findByRetrospectId(retrospectId)
                .map(com.momentory.actioncard.domain.ActionCard::getId);
    }

}
