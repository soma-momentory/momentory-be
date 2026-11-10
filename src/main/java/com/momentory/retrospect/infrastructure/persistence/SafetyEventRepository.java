package com.momentory.retrospect.infrastructure.persistence;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SafetyEventRepository extends JpaRepository<SafetyEvent, Long> {

    /**
     * 회고 턴 트랜잭션 안에서 한 줄 남긴다 — 턴이 롤백되면 기록도 없다. 같은 회고 · 같은 등급이 다시 와도
     * (재전송 · 「이어서 얘기하기」 뒤 재상승) 아무것도 하지 않는다.
     */
    @Modifying
    @Query(value = """
            insert into safety_events (user_id, retrospect_id, level, occurred_at)
            values (:userId, :retrospectId, :level, now())
            on conflict do nothing
            """, nativeQuery = true)
    int record(@Param("userId") Long userId, @Param("retrospectId") Long retrospectId,
            @Param("level") String level);

    /** 이 순간 이후 위기 기록이 있는가 — 편지는 일기 날 04:00(KST)을 넘긴다. */
    boolean existsByUserIdAndOccurredAtGreaterThanEqual(Long userId, Instant since);
}
