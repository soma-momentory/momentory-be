package com.momentory.retrospect.presentation;

import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.ScheduleItem;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 시작 요청의 일정 하나.
 *
 * @param name    일정 이름. 필수.
 * @param emotion 이 일정에 연결된 감정 키. 필수. (9종: anxious·depressed·angry·happy·stuck·lethargic·tired·proud·calm)
 */
public record ScheduleItemRequest(
        @Schema(description = "일정 이름", example = "면접 스터디") String name,
        @Schema(description = "이 일정에 연결된 감정 키", example = "anxious") String emotion) {

    ScheduleItem toDomain(int index) {
        if (name == null || name.isBlank()) {
            throw new InvalidStartException("schedules[%d].name (일정 이름) 은 필수입니다."
                    .formatted(index));
        }
        Emotion tagged = Emotion.fromKey(emotion)
                .orElseThrow(() -> InvalidStartException.badEmotion(
                        "schedules[%d].emotion".formatted(index), emotion));
        return new ScheduleItem(name.strip(), tagged);
    }
}
