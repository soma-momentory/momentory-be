package com.momentory.wish.infrastructure;

import com.momentory.wish.domain.Wish;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface WishRepository extends JpaRepository<Wish, Long> {

    /** 연못에 줄 소원 한 장. */
    interface PondRow {
        Long getId();

        String getContent();

        Integer getDaysLeft();

        Boolean getLight();

        Boolean getJoined();
    }

    boolean existsByUserIdAndWishDate(Long userId, LocalDate wishDate);

    @Query("""
            select count(w) > 0 from Wish w
            where w.userId = :userId and w.deletedAt is null and w.takenDownAt is null and w.expiresAt > :now
            """)
    boolean hasFloating(@Param("userId") Long userId, @Param("now") Instant now);

    List<Wish> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(Long userId);

    /**
     * 남의 소원을 무작위로 고른다. 내 소원과 내가 신고한 사람의 소원은 뺀다. 같은 사람 소원은 하루에
     * 한 장만 보이도록 사람마다 날짜로 정해지는 한 장을 고르고, 아직 같이 빌지 않은 소원을 앞에 둔다.
     */
    @Query(value = """
            SELECT p.id AS id,
                   p.content AS content,
                   CAST(CEIL(EXTRACT(EPOCH FROM (p.expires_at - :now)) / 86400) AS INTEGER) AS daysLeft,
                   p.light AS light,
                   EXISTS (SELECT 1 FROM wish_joins j WHERE j.wish_id = p.id AND j.user_id = :me) AS joined
            FROM (
                SELECT DISTINCT ON (w.user_id) w.*
                FROM wishes w
                WHERE w.deleted_at IS NULL AND w.taken_down_at IS NULL AND w.expires_at > :now
                  AND w.user_id <> :me
                  AND w.user_id NOT IN (SELECT r.author_id FROM wish_reports r WHERE r.reporter_id = :me)
                ORDER BY w.user_id, md5(CAST(w.id AS TEXT) || :day)
            ) p
            ORDER BY joined ASC, random()
            LIMIT :limit
            """, nativeQuery = true)
    List<PondRow> pickForPond(@Param("me") Long me, @Param("now") Instant now, @Param("day") String day,
            @Param("limit") int limit);

    /** 연못에 떠 있는 남의 소원 수 — 꽃의 규모를 정할 뿐 화면에 숫자로 보이지 않는다. */
    @Query(value = """
            SELECT COUNT(DISTINCT w.user_id) FROM wishes w
            WHERE w.deleted_at IS NULL AND w.taken_down_at IS NULL AND w.expires_at > :now
              AND w.user_id <> :me
              AND w.user_id NOT IN (SELECT r.author_id FROM wish_reports r WHERE r.reporter_id = :me)
            """, nativeQuery = true)
    long countFloatingAuthors(@Param("me") Long me, @Param("now") Instant now);
}
