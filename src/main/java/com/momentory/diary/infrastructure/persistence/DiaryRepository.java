package com.momentory.diary.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DiaryRepository extends JpaRepository<Diary, Long> {

    /** 그날의 회고에 딸린 일기 — 회고 한 벌에 하나뿐이다. */
    Optional<Diary> findByRetrospectId(Long retrospectId);

    boolean existsByRetrospectId(Long retrospectId);

    /** 단건 조회 + 소유권 검증 — 남의 일기는 못 연다. */
    Optional<Diary> findByIdAndUserId(Long id, Long userId);

    /**
     * 이 사용자가 {@code [start, end)} 구간에 남긴 일기가 있는가 — 회고 "하루 한 번" 가드에 쓴다.
     * 오늘 하루(KST)를 서비스가 계산해 넘긴다. 일기는 회고 완료 때만 생기므로, 오늘 일기가 있다는
     * 건 오늘 회고를 이미 완주했다는 뜻이다.
     */
    boolean existsByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            Long userId, Instant start, Instant end);

    /**
     * 한 달치 일기 — {@code [start, end)} 반열림 구간(월 경계는 서비스가 KST 로 계산한다).
     * 최신순. {@code idx_diaries_user_created (user_id, created_at)} 를 탄다.
     */
    List<Diary> findByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
            Long userId, Instant start, Instant end);
}
