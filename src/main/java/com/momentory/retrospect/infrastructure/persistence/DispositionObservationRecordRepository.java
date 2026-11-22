package com.momentory.retrospect.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DispositionObservationRecordRepository extends JpaRepository<DispositionObservationRecord, Long> {

    /** 완료된 회고의 최종 관찰만 읽으며 진행 중/안전 중단/다른 사용자의 회고는 쓰지 않는다. */
    @Query("select o from DispositionObservationRecord o join Retrospect r on r.id = o.retrospectId"
            + " where o.userId = :userId and r.userId = :userId and o.observedTurns > 0"
            + " and r.status = com.momentory.retrospect.domain.RetrospectStatus.COMPLETED"
            + " order by r.completedAt desc nulls last, r.id desc")
    List<DispositionObservationRecord> findCompletedByUserId(@Param("userId") Long userId, Pageable pageable);

    Optional<DispositionObservationRecord> findByRetrospectId(Long retrospectId);

}
