package com.momentory.worry.presentation;

import com.momentory.worry.application.WorryResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 맡겨둔 걱정 목록 — 최신순. 비면 빈 배열. */
public record WorryListResponse(
        @Schema(description = "맡겨둔 걱정 목록") List<WorryResponse> worries
) {

    static WorryListResponse from(List<WorryResult> results) {
        return new WorryListResponse(results.stream().map(WorryResponse::from).toList());
    }
}
