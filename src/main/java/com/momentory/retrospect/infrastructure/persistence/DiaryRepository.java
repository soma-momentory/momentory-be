package com.momentory.retrospect.infrastructure.persistence;

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
     * 한 달치 일기 — {@code [start, end)} 반열림 구간(월 경계는 서비스가 KST 로 계산한다).
     * 최신순. {@code idx_diaries_user_created (user_id, created_at)} 를 탄다.
     */
    List<Diary> findByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
            Long userId, Instant start, Instant end);
}
