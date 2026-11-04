package com.momentory.wish.presentation;

import com.momentory.wish.domain.Wish;
import com.momentory.wish.domain.WishOutcome;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 소원 API 요청들. */
public final class WishRequests {

    private WishRequests() {
    }

    public record Review(
            @Schema(description = "심사할 소원(최대 120자)", example = "내일 면접에서 떨지 않으면 좋겠어요")
            @NotBlank(message = "소원을 입력해주세요.")
            @Size(max = Wish.CONTENT_MAX_LENGTH, message = "소원은 최대 120자입니다.")
            String content
    ) {
        public Review {
            content = content == null ? null : content.strip();
        }
    }

    public record Post(
            @Schema(description = "심사가 돌려준 글 그대로", example = "내일 면접에서 떨지 않으면 좋겠어요")
            @NotBlank(message = "소원을 입력해주세요.")
            @Size(max = Wish.CONTENT_MAX_LENGTH, message = "소원은 최대 120자입니다.")
            String content,
            @Schema(description = "심사가 돌려준 표")
            @NotBlank(message = "소원을 다시 확인해주세요.")
            String reviewToken
    ) {
    }

    public record Answer(
            @Schema(description = "지나간 소원에 답하기", example = "CAME_TRUE")
            @NotNull(message = "답을 골라주세요.")
            WishOutcome outcome
    ) {
    }
}
