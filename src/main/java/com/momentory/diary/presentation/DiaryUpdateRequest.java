package com.momentory.diary.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 일기 본문 수정 요청 — 회고 완료 화면(C6)의 직접 고치기. 「내가 남긴 오늘」 본문({@code original})만
 * 바꾼다. 바바가 다시 본 오늘·감정은 건드리지 않는다.
 */
public record DiaryUpdateRequest(
        @Schema(description = "고친 일기 본문", example = "오늘은 생각보다 잘 넘겼다.")
        @NotBlank(message = "일기 본문을 입력해주세요.")
        String original) {
}
