package com.momentory.worry.presentation;

import com.momentory.worry.domain.Worry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WorryRequest(
        @Schema(description = "맡겨둘 걱정 한 줄(최대 100자)", example = "내일 발표가 잘 안될까 봐 걱정돼요")
        @NotBlank(message = "걱정 내용을 입력해주세요.")
        @Size(max = Worry.CONTENT_MAX_LENGTH, message = "걱정 내용은 최대 100자입니다.")
        String content
) {

    public WorryRequest {
        content = content == null ? null : content.strip();
    }
}
