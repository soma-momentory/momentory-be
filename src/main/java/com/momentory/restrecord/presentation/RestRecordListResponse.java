package com.momentory.restrecord.presentation;

import com.momentory.restrecord.application.RestRecordResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 한 쉼터의 이용 기록 — 최신순. 비면 빈 배열. */
public record RestRecordListResponse(
        @Schema(description = "이용 기록 목록") List<RestRecordResponse> records
) {

    static RestRecordListResponse from(List<RestRecordResult> results) {
        return new RestRecordListResponse(results.stream().map(RestRecordResponse::from).toList());
    }
}
