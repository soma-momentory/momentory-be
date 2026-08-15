package com.momentory.diary.presentation;

import java.util.List;

import com.momentory.diary.application.DiaryView;

import io.swagger.v3.oas.annotations.media.Schema;

/** 월별 일기 목록 응답 — 최신순. 비면 빈 배열. */
public record DiaryListResponse(
        @Schema(description = "일기 목록(최신순)") List<DiaryResponse> diaries) {

    static DiaryListResponse from(List<DiaryView> views) {
        return new DiaryListResponse(views.stream().map(DiaryResponse::from).toList());
    }
}
