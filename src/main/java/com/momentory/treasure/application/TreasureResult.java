package com.momentory.treasure.application;

import com.momentory.treasure.domain.Treasure;

import java.time.Instant;
import java.time.LocalDate;

public record TreasureResult(Long id, LocalDate date, String content, Instant createdAt) {

    public static TreasureResult from(Treasure treasure) {
        return new TreasureResult(treasure.getId(), treasure.getTreasureDate(), treasure.getContent(), treasure.getCreatedAt());
    }
}
