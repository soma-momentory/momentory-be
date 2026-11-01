package com.momentory.diary.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 직접 쓰기와 id 기반 본문 수정은 앱의 500자 상한을 공유한다. */
public record DiaryWriteRequest(
        @Schema(description = "직접 쓴 일기 본문", maxLength = 500)
        @NotBlank(message = "일기 본문을 입력해주세요.")
        @Size(max = 500, message = "일기는 500자까지 쓸 수 있어요.")
        String original) {
}
