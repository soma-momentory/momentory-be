package com.momentory.retrospect.infrastructure.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TurnJudgmentRecordRepository extends JpaRepository<TurnJudgmentRecord, Long> {

    List<TurnJudgmentRecord> findByRetrospectIdOrderByDiaryTurn(Long retrospectId);
}
