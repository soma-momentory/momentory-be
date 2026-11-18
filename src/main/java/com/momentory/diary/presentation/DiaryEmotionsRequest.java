package com.momentory.diary.presentation;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DiaryEmotionsRequest(
        @Schema(description = "일기에 있는 모든 사건 번호와 선택 감정. 사건을 추가하거나 생략할 수 없음")
        @NotNull(message = INVALID) @Size(max = 20, message = INVALID)
        List<@NotNull(message = INVALID) @Valid EventSelection> events,
        @Schema(description = "사건 없이 느낀 감정. 모두 해제하려면 빈 배열")
        @NotNull(message = INVALID) @Size(max = 12, message = INVALID)
        List<@NotNull(message = INVALID) @Pattern(regexp = KEYS, message = INVALID) String> unlinkedEmotions) {
    private static final String INVALID = "감정과 사건을 확인해주세요.";
    private static final String KEYS = "joy|calm|proud|affection|sad|anxious|angry|disappointed|ashamed|confused|sorry|exhausted";

    public record EventSelection(
            @NotNull(message = INVALID) @Min(value = 1, message = INVALID) Integer eventId,
            @NotNull(message = INVALID) @Size(max = 12, message = INVALID)
            List<@NotNull(message = INVALID) @Pattern(regexp = KEYS, message = INVALID) String> emotions) {}
}
