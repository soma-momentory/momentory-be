package com.momentory.retrospect.application;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 오늘 진행 중인 회고 — 앱을 껐다 켜도 같은 세션을 이어서 하도록, 지금까지의 대화와 마지막 응답을 함께 준다.
 * 이후 턴은 이 {@code sessionId} 로 그대로 보낸다.
 *
 * @param messages 지금까지 나눈 말(오래된 순). 바바의 말에 붙어 있던 선택지 줄은 빼고 준다.
 * @param reply    마지막으로 받은 응답. 선택지가 있으면 그 버튼을, 없으면 자유 입력을 연다.
 */
public record CurrentRetrospectDto(
        @Schema(description = "세션 식별자", example = "1") Long sessionId,
        List<MessageDto> messages,
        ReplyDto reply) {

    public CurrentRetrospectDto {
        messages = List.copyOf(messages);
    }

    /** 대화 한 줄. */
    public record MessageDto(
            @Schema(description = "누가 한 말인지", allowableValues = {"assistant", "user"}, example = "assistant")
            String role,
            @Schema(description = "말 내용", example = "오늘 하루 중 가장 기억에 남는 일은 뭐였어요?")
            String text) {
    }
}
