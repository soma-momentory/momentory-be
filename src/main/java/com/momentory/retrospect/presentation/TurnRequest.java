package com.momentory.retrospect.presentation;

import java.util.List;

import com.momentory.retrospect.application.TurnCommand;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 한 턴의 사용자 입력 (채팅흐름_v2) — 자유 텍스트이거나 선택지들이다.
 *
 * @param content   자유 텍스트 답변("직접 적기" 포함)
 * @param optionIds 고른 선택지 id 들(응답 {@code options[].id}) — 분기점은 1개, 감정·바람은 최대 2개
 */
public record TurnRequest(
        @Schema(description = "자유 텍스트 답변", example = "발표에서 말이 막혀서 계속 곱씹게 돼요.")
        String content,
        @Schema(description = "고른 선택지 id 들(분기점 1개, 감정·바람 최대 2개)", example = "[\"1\", \"3\"]")
        List<String> optionIds) {

    TurnCommand toDomain() {
        return new TurnCommand(content, optionIds);
    }
}
