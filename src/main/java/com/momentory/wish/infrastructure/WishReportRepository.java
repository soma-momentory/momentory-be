package com.momentory.wish.infrastructure;

import com.momentory.wish.domain.WishReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WishReportRepository extends JpaRepository<WishReport, Long> {

    /** 이미 신고했으면 아무 일도 하지 않는다. */
    @Modifying
    @Query(value = """
            INSERT INTO wish_reports (wish_id, reporter_id, author_id) VALUES (:wishId, :reporterId, :authorId)
            ON CONFLICT (wish_id, reporter_id) DO NOTHING
            """, nativeQuery = true)
    void reportOnce(@Param("wishId") Long wishId, @Param("reporterId") Long reporterId,
            @Param("authorId") Long authorId);

    long countByWishId(Long wishId);

    @Modifying
    @Query("delete from WishReport r where r.wishId = :wishId and r.reporterId = :reporterId")
    void undo(@Param("wishId") Long wishId, @Param("reporterId") Long reporterId);
}
