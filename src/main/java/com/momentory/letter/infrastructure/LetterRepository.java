package com.momentory.letter.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.momentory.letter.domain.Letter;

import jakarta.persistence.LockModeType;

public interface LetterRepository extends JpaRepository<Letter, Long> {

    /**
     * 회고를 마친 트랜잭션 안에서 「만들어야 함」 행을 남긴다. 같은 일기 · 같은 날이 두 번 와도
     * 실패하지 않게 두 유일 제약 모두에 대해 아무것도 하지 않는다.
     */
    @Modifying
    @Query(value = """
            insert into letters (user_id, diary_id, letter_date, status, created_at, updated_at)
            values (:userId, :diaryId, :letterDate, 'PENDING', now(), now())
            on conflict do nothing
            """, nativeQuery = true)
    int insertPending(@Param("userId") Long userId, @Param("diaryId") Long diaryId,
            @Param("letterDate") LocalDate letterDate);

    /**
     * 만들기에 들어간다 — 뒤에서 만들기와 열 때 보충이 동시에 와도 한쪽만 1을 받는다.
     * 들어갈 수 있는 경우: 아직 안 만듦 · 실패 · 멈춘 지 오래된 GENERATING(서버 재시작 등) ·
     * 아직 안 연 편지의 일기를 고침. 들어가면서 {@code stale} 을 내린다 — 만드는 중에 일기를 또
     * 고치면 다시 올라가 다음에 열 때 한 번 더 만든다.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Letter l
               set l.status = com.momentory.letter.domain.LetterStatus.GENERATING,
                   l.generationStartedAt = :now,
                   l.stale = false,
                   l.attemptCount = l.attemptCount + 1,
                   l.updatedAt = :now
             where l.id = :id
               and (l.status in (com.momentory.letter.domain.LetterStatus.PENDING,
                                 com.momentory.letter.domain.LetterStatus.FAILED)
                    or (l.status = com.momentory.letter.domain.LetterStatus.GENERATING
                        and l.generationStartedAt < :stuckBefore)
                    or (l.stale = true and l.openedAt is null
                        and l.status in (com.momentory.letter.domain.LetterStatus.READY,
                                         com.momentory.letter.domain.LetterStatus.FALLBACK,
                                         com.momentory.letter.domain.LetterStatus.QUIET)))
            """)
    int claim(@Param("id") Long id, @Param("now") Instant now, @Param("stuckBefore") Instant stuckBefore);

    /** 일기를 고쳤다 — 이미 열어 본 편지는 다시 만들지 않는다. */
    @Modifying
    @Query("update Letter l set l.stale = true where l.diaryId = :diaryId and l.openedAt is null")
    int markStale(@Param("diaryId") Long diaryId);

    Optional<Letter> findByDiaryId(Long diaryId);

    /** 저장 직전 행을 잠근다 — 주인 확인과 쓰기 사이에 다른 쪽이 들어오지 못하게. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Letter l where l.id = :id")
    Optional<Letter> findForUpdate(@Param("id") Long id);

    /** 작은 일 고르기 — 같은 편지에 고르기가 겹쳐 「하나만 고름」 인덱스에 걸리지 않게 줄 세운다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Letter l where l.id = :id and l.userId = :userId")
    Optional<Letter> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    Optional<Letter> findByIdAndUserId(Long id, Long userId);

    Optional<Letter> findByUserIdAndLetterDate(Long userId, LocalDate letterDate);

    /** 이 날 전까지(열 수 있는) 가장 최근 편지. */
    Optional<Letter> findFirstByUserIdAndLetterDateLessThanOrderByLetterDateDesc(Long userId, LocalDate before);

    /** 넘겨 보기 목록 — 하루 한 통이라 몇 해가 쌓여도 수백 행이다. */
    List<Letter> findByUserIdAndLetterDateLessThanOrderByLetterDateDesc(Long userId, LocalDate before);
}
