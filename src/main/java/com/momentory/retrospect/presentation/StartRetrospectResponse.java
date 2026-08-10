package com.momentory.retrospect.presentation;

import com.momentory.retrospect.application.ReplyDto;
import com.momentory.retrospect.application.RetrospectResult;

import io.swagger.v3.oas.annotations.media.Schema;

/** 회고 시작 응답. 이후 턴은 이 {@code sessionId} 를 경로에 실어 보낸다. */
public record StartRetrospectResponse(
        @Schema(description = "세션 식별자", example = "1") Long sessionId,
        ReplyDto reply) {

    static StartRetrospectResponse from(RetrospectResult result) {
        return new StartRetrospectResponse(result.id(), result.reply());
    }
}
