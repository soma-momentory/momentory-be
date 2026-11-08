package com.momentory.wish.infrastructure;

import com.momentory.wish.domain.WishJoin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface WishJoinRepository extends JpaRepository<WishJoin, Long> {

    /** 이미 같이 빌었으면 아무 일도 하지 않는다. */
    @Modifying
    @Query(value = """
            INSERT INTO wish_joins (wish_id, user_id) VALUES (:wishId, :userId)
            ON CONFLICT (wish_id, user_id) DO NOTHING
            """, nativeQuery = true)
    void joinOnce(@Param("wishId") Long wishId, @Param("userId") Long userId);

    @Query("select distinct j.wishId from WishJoin j where j.wishId in :wishIds")
    List<Long> findJoinedWishIds(@Param("wishIds") Collection<Long> wishIds);
}
