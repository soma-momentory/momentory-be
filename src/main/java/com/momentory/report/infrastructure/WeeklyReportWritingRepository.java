package com.momentory.report.infrastructure;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.momentory.report.domain.WeeklyReportWriting;

public interface WeeklyReportWritingRepository extends JpaRepository<WeeklyReportWriting, Long> {

    Optional<WeeklyReportWriting> findByUserIdAndWeekStart(Long userId, LocalDate weekStart);
}
