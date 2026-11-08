package com.momentory.restrecord.infrastructure;

import com.momentory.restrecord.domain.RestContent;
import com.momentory.restrecord.domain.RestRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface RestRecordRepository extends JpaRepository<RestRecord, Long> {

    List<RestRecord> findByUserIdAndContentOrderByCreatedAtDescIdDesc(Long userId, RestContent content);

    /** 끝까지 한 기록 — 주간 리포트가 그 주와 쌓인 답을 센다. */
    List<RestRecord> findByUserIdAndCompletedTrueAndRecordDateLessThanEqualOrderByRecordDateAscIdAsc(Long userId, LocalDate to);
}
