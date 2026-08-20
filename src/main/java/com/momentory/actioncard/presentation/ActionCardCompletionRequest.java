package com.momentory.actioncard.presentation;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * "해봤어요"/되돌리기 요청. {@code done=false}(되돌리기)면 느낀 점을 함께 비우므로 담을 수 없다 —
 * 느낀 점은 "해본 뒤 한 줄"이라 완료 상태에만 매달린다.
 */
public record ActionCardCompletionRequest(
        @Schema(example = "true")
        @NotNull(message = "완료 여부를 입력해주세요.")
        Boolean done,
        @Schema(nullable = true, description = "느낀 점 — 완료 상태에서만", example = "해보니 생각보다 괜찮았다")
        String reflection) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "되돌린 상태에서는 느낀 점을 남길 수 없습니다.")
    public boolean isReflectionValid() {
        return done == null || done || reflection == null;
    }
}
