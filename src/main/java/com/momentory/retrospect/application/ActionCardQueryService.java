package com.momentory.retrospect.application;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.momentory.common.time.TimeZonePolicy;
import com.momentory.retrospect.infrastructure.persistence.ActionCardRepository;

/**
 * 행동 카드 조회 유스케이스 — 보관함의 월별 목록과 단건 조회. 카드 생성은 회고 완료 흐름
 * ({@link RetrospectService}) 이 맡고, 여기선 읽기만 한다. 일기 월별 조회
 * ({@link DiaryQueryService}) 와 같은 {@code created_at} KST 월 경계를 쓴다.
 */
@Service
public class ActionCardQueryService {

    private static final ZoneId ZONE = TimeZonePolicy.DEFAULT_ZONE_ID;

    private final ActionCardRepository actionCardRepository;

    public ActionCardQueryService(ActionCardRepository actionCardRepository) {
        this.actionCardRepository = actionCardRepository;
    }

    /**
     * 한 달치 행동 카드(최신순). 월 경계는 KST 기준으로 잡아 {@code [해당 월 1일 00:00,
     * 다음 달 1일 00:00)} 반열림 구간으로 조회한다.
     *
     * @throws java.time.DateTimeException 월이 1~12 범위를 벗어나면(표현 계층이 400 으로 번역)
     */
    @Transactional(readOnly = true)
    public List<ActionCardView> getMonthly(Long userId, int year, int month) {
        YearMonth target = YearMonth.of(year, month);
        Instant start = target.atDay(1).atStartOfDay(ZONE).toInstant();
        Instant end = target.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant();
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
}
