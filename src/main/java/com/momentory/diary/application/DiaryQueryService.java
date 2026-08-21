package com.momentory.diary.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.momentory.common.time.TimeZonePolicy;
import com.momentory.diary.infrastructure.DiaryRepository;

/**
 * 일기 조회 유스케이스 — 보관함의 월별 목록·단건, 그리고 회고 화면이 쓰는 회고별 단건. 쓰기(생성)는
 * {@code RetrospectCompleted} 이벤트를 받는 {@link DiaryFromRetrospectListener} 가 맡고,
 * 여기선 읽기만 한다.
 */
@Service
public class DiaryQueryService {

    private static final ZoneId ZONE = TimeZonePolicy.DEFAULT_ZONE_ID;

    private final DiaryRepository diaryRepository;

    public DiaryQueryService(DiaryRepository diaryRepository) {
        this.diaryRepository = diaryRepository;
    }

    /**
     * 한 달치 일기(최신순). 월 경계는 KST 기준으로 잡아 {@code [해당 월 1일 00:00, 다음 달 1일 00:00)}
     * 반열림 구간으로 조회한다.
     *
     * @throws java.time.DateTimeException 월이 1~12 범위를 벗어나면(표현 계층이 400 으로 번역)
     */
    @Transactional(readOnly = true)
    public List<DiaryView> getMonthly(Long userId, int year, int month) {
        YearMonth target = YearMonth.of(year, month);
        Instant start = target.atDay(1).atStartOfDay(ZONE).toInstant();
        Instant end = target.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant();
        return diaryRepository
                .findByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                        userId, start, end)
                .stream()
                .map(DiaryView::from)
                .toList();
    }

    /**
     * 이 사용자의 일기 전체(최신순) — 보관함 리스트 뷰가 쓴다. 월 필터 없이 통째로 훑는다.
     */
    @Transactional(readOnly = true)
    public List<DiaryView> getAll(Long userId) {
        return diaryRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(DiaryView::from)
                .toList();
    }

    /** 일기 단건 — 소유권을 함께 검증한다. */
    @Transactional(readOnly = true)
    public DiaryView getOne(Long userId, Long id) {
        return diaryRepository.findByIdAndUserId(id, userId)
                .map(DiaryView::from)
                .orElseThrow(DiaryNotFoundException::new);
    }

    /**
     * 그 날(KST)에 이 사용자의 일기가 있는가 — "회고 하루 한 번" 가드가 쓴다. 일기의 날짜 경계
     * 계산(KST 반열림 구간)은 diary 의 몫이라 여기에 둔다.
     */
    @Transactional(readOnly = true)
    public boolean hasDiaryOn(Long userId, LocalDate date) {
        Instant start = date.atStartOfDay(ZONE).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(ZONE).toInstant();
        return diaryRepository.existsByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                userId, start, end);
    }
}
