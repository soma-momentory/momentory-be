package com.momentory.weeklyhappiness.presentation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.momentory.weeklyhappiness.domain.WeeklyHappinessCategory;
import com.momentory.weeklyhappiness.domain.WeeklyHappinessEntry;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 한 주의 기록장 전체 — 보내지 않은 칸과 비운 칸은 지워진다. */
public record WeeklyHappinessRequest(
        @ArraySchema(arraySchema = @Schema(description = "적은 칸들 — 칸마다 한 번씩. 빈 배열이면 그 주를 모두 비운다"))
        @NotNull(message = "적은 칸 목록을 보내주세요.")
        List<@NotNull(message = "잘못된 요청입니다.") @Valid WeeklyHappinessEntryRequest> entries
) {

    public WeeklyHappinessRequest {
        // null 칸은 검증이 400 으로 돌려보내도록 그대로 둔다(List.copyOf 는 null 을 받지 않는다)
        entries = entries == null ? null : Collections.unmodifiableList(new ArrayList<>(entries));
    }

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "같은 칸을 두 번 보낼 수 없습니다.")
    public boolean isCategoriesUnique() {
        if (entries == null) {
            return true;
        }
        List<WeeklyHappinessCategory> categories = entries.stream()
                .filter(Objects::nonNull)
                .map(WeeklyHappinessEntryRequest::category)
                .filter(Objects::nonNull)
                .toList();
        return categories.stream().distinct().count() == categories.size();
    }

    Map<WeeklyHappinessCategory, String> entriesByCategory() {
        Map<WeeklyHappinessCategory, String> byCategory = new EnumMap<>(WeeklyHappinessCategory.class);
        entries.forEach(entry -> byCategory.put(entry.category(), entry.content()));
        return byCategory;
    }

    public record WeeklyHappinessEntryRequest(
            @Schema(description = "칸", example = "FOOD")
            @NotNull(message = "칸을 골라주세요.")
            WeeklyHappinessCategory category,
            @Schema(description = "적은 글(최대 100자) — 비우면 그 칸을 지운다", nullable = true, example = "오랜만에 먹은 마라탕")
            @Size(max = WeeklyHappinessEntry.CONTENT_MAX_LENGTH, message = "주간 행복 한 칸은 최대 100자입니다.")
            String content
    ) {

        public WeeklyHappinessEntryRequest {
            content = content == null ? null : content.strip();
        }
    }
}
