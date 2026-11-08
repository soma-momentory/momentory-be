package com.momentory.diary.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 직접 쓴 본문과 사용자가 사진 자리에서 고른 감정 하나. 감정은 선택 사항이다. */
public record DiaryCreateRequest(
        @Schema(description = "직접 쓴 일기 본문", maxLength = 500)
        @NotBlank(message = "일기 본문을 입력해주세요.")
        @Size(max = 500, message = "일기는 500자까지 쓸 수 있어요.")
        String original,
        @Schema(description = "사용자가 선택한 감정 키. 선택하지 않으면 생략",
                allowableValues = { "happy", "proud", "calm", "tired", "stuck", "lethargic", "anxious", "depressed", "angry", "frustrated" })
        @Pattern(regexp = "happy|proud|calm|tired|stuck|lethargic|anxious|depressed|angry|frustrated",
                message = "감정을 확인해주세요.")
        String emotion) {
}
