package com.momentory.weeklyhappiness.infrastructure;

import com.momentory.weeklyhappiness.domain.WeeklyHappinessCategory;
import com.momentory.weeklyhappiness.domain.WeeklyHappinessEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface WeeklyHappinessEntryRepository extends JpaRepository<WeeklyHappinessEntry, Long> {

    List<WeeklyHappinessEntry> findByUserIdAndWeekStart(Long userId, LocalDate weekStart);

    /** 적은 칸이 하나라도 있는 가장 이른 주 — 없으면 {@code null}. */
    @Query("select min(e.weekStart) from WeeklyHappinessEntry e where e.userId = :userId")
    LocalDate findFirstWeekStart(@Param("userId") Long userId);

    /**
     * 이 사용자의 이 주를 트랜잭션이 끝날 때까지 잠근다 — 같은 주를 동시에 통째로 바꾸는 두 요청이 서로의
     * 칸을 지우지 못하고 섞이는 것을 막는다(뒤에 온 요청이 앞 요청이 끝난 뒤의 상태 위에서 바꾼다).
     */
    @Query(value = """
            SELECT 1 FROM (
                SELECT pg_advisory_xact_lock(hashtextextended('weekly_happiness:' || CAST(:userId AS text) || ':' || CAST(:weekStart AS text), 0))
            ) AS locked
            """, nativeQuery = true)
    Integer lockWeek(@Param("userId") Long userId, @Param("weekStart") LocalDate weekStart);

    /** 한 칸을 적거나 고친다 — 같은 칸을 동시에 보내도 유일 제약 위에서 한 줄로 모인다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO weekly_happiness_entries (user_id, week_start, category, content, created_at, updated_at)
            VALUES (:userId, :weekStart, :category, :content, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (user_id, week_start, category)
            DO UPDATE SET content = EXCLUDED.content, updated_at = CURRENT_TIMESTAMP
            WHERE weekly_happiness_entries.content IS DISTINCT FROM EXCLUDED.content
            """, nativeQuery = true)
    void upsert(@Param("userId") Long userId, @Param("weekStart") LocalDate weekStart,
                @Param("category") String category, @Param("content") String content);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from WeeklyHappinessEntry e
            where e.userId = :userId and e.weekStart = :weekStart and e.category in :categories
            """)
    void deleteCategories(@Param("userId") Long userId, @Param("weekStart") LocalDate weekStart,
                          @Param("categories") Collection<WeeklyHappinessCategory> categories);
}
