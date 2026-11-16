package com.momentory.retrospect.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DispositionObservationRecordRepository extends JpaRepository<DispositionObservationRecord, Long> {

    Optional<DispositionObservationRecord> findByRetrospectId(Long retrospectId);

    /** 답변이 하나라도 관찰된 대화를 최근 것부터 — 시작만 하고 떠난 대화는 성향을 말해 주지 않는다. */
    List<DispositionObservationRecord> findByUserIdAndObservedTurnsGreaterThanOrderByCreatedAtDesc(Long userId,
            int observedTurns, Pageable pageable);
}
