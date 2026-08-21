package com.momentory.diary.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.momentory.common.time.TimeZonePolicy;
import com.momentory.diary.domain.Diary;
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.retrospect.domain.Emotion;

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

    /**
     * {@code [from, to]}(KST, 양끝 포함) 안에서 하루별 현재 감정 — 주간 리포트의 「이번 주의 마음」이
     * 쓴다. 일기는 하루 한 벌만 남으므로(회고 "하루 한 번" 가드) 날짜 하나에 감정 하나다. 혹시 같은
     * 날에 둘이 있으면 최신 것을 쓴다. 일기가 없는 날은 키 자체가 없다 — 빈 칸은 부르는 쪽이 채운다.
     *
     * <p>본문은 담지 않는다 — 세는 화면에 일기 내용을 흘리지 않기 위해서다.
     */
    @Transactional(readOnly = true)
    public Map<LocalDate, Emotion> getDailyEmotions(Long userId, LocalDate from, LocalDate to) {
        Instant start = from.atStartOfDay(ZONE).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(ZONE).toInstant();
        Map<LocalDate, Emotion> byDate = new LinkedHashMap<>();
        // 최신순으로 오므로, 같은 날에 둘이 있으면 먼저 담기는 최신 것이 남는다.
        for (Diary diary : diaryRepository
                .findByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                        userId, start, end)) {
            byDate.putIfAbsent(LocalDate.ofInstant(diary.getCreatedAt(), ZONE),
                    diary.getCurrentEmotion());
        }
        return Collections.unmodifiableMap(byDate);
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
