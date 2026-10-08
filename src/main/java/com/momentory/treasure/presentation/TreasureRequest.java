package com.momentory.treasure.presentation;

import com.momentory.treasure.domain.Treasure;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TreasureRequest(
        @Schema(description = "담을 소중한 순간 한 줄(최대 100자)", example = "친구에게 먼저 연락했어요.")
        @NotBlank(message = "보물 내용을 입력해주세요.")
        @Size(max = Treasure.CONTENT_MAX_LENGTH, message = "보물 내용은 최대 100자입니다.")
        String content
) {

    public TreasureRequest {
        content = content == null ? null : content.strip();
    }
}
