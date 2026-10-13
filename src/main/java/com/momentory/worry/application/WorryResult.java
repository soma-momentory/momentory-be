package com.momentory.worry.application;

import com.momentory.worry.domain.Worry;

import java.time.Instant;
import java.time.LocalDate;

public record WorryResult(Long id, LocalDate date, String content, Instant createdAt) {

    public static WorryResult from(Worry worry) {
        return new WorryResult(worry.getId(), worry.getWorryDate(), worry.getContent(), worry.getCreatedAt());
    }
}
