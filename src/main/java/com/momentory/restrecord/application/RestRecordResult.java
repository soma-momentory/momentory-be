package com.momentory.restrecord.application;

import com.momentory.restrecord.domain.RestContent;
import com.momentory.restrecord.domain.RestMood;
import com.momentory.restrecord.domain.RestRecord;

import java.time.Instant;
import java.time.LocalDate;

public record RestRecordResult(Long id, RestContent content, LocalDate date, boolean completed, RestMood mood, Instant createdAt) {

    public static RestRecordResult from(RestRecord record) {
        return new RestRecordResult(record.getId(), record.getContent(), record.getRecordDate(), record.isCompleted(), record.getMood(), record.getCreatedAt());
    }
}
