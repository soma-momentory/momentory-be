package com.momentory.restrecord.infrastructure;

import com.momentory.restrecord.domain.RestContent;
import com.momentory.restrecord.domain.RestRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RestRecordRepository extends JpaRepository<RestRecord, Long> {

    List<RestRecord> findByUserIdAndContentOrderByCreatedAtDescIdDesc(Long userId, RestContent content);
}
