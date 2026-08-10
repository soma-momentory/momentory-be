package com.momentory.retrospect.presentation;

import java.util.Map;

import com.momentory.retrospect.application.TurnCommand;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 한 턴의 사용자 입력 — 셋 중 그 턴에 맞는 하나만 채워 보낸다.
 *
 * @param content  자유 텍스트 답변
 * @param optionId 선택지 턴에서 고른 버튼 id (응답 {@code options[].id} 를 그대로)
 * @param measures 측정 턴에서만. 슬라이더 id → 0~10 값.
 */
public record TurnRequest(
        @Schema(description = "자유 텍스트 답변", example = "발표에서 말이 막혀서 계속 곱씹게 돼요.")
        String content,
        @Schema(description = "선택지 턴에서 고른 버튼 id", example = "1")
        String optionId,
        @Schema(description = "측정 턴의 슬라이더 값(0~10)", example = "{\"current_emotion\": 6}")
        Map<String, Integer> measures) {

    TurnCommand toDomain() {
        return new TurnCommand(content, optionId, measures);
    }
}
