package com.momentory.actioncard.presentation;

import java.util.List;

import com.momentory.actioncard.application.ActionCardView;

import io.swagger.v3.oas.annotations.media.Schema;

/** 월별 행동 카드 목록 응답 — 최신순. 비면 빈 배열. */
public record ActionCardListResponse(
        @Schema(description = "행동 카드 목록(최신순)") List<ActionCardResponse> actionCards) {

    static ActionCardListResponse from(List<ActionCardView> views) {
        return new ActionCardListResponse(views.stream().map(ActionCardResponse::from).toList());
    }
}
