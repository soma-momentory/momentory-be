package com.momentory.treasure.presentation;

import com.momentory.treasure.application.TreasureResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 보물 목록 — 날짜를 주면 그날 것을 담은 순서대로, 없으면 전부를 최신순으로. 비면 빈 배열. */
public record TreasureListResponse(
        @Schema(description = "보물 목록") List<TreasureResponse> treasures
) {

    static TreasureListResponse from(List<TreasureResult> results) {
        return new TreasureListResponse(results.stream().map(TreasureResponse::from).toList());
    }
}
