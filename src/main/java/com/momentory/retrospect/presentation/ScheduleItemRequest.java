package com.momentory.retrospect.presentation;

import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.ScheduleItem;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 시작 요청의 일정 하나.
 *
 * @param id      schedules 테이블의 일정 id. <b>선택</b> — 목록에서 고른 일정이면 그 id, 사용자가
 *                직접 타이핑한 자유 입력 일정이면 비운다(null). 소유권은 서버가 저장 전 검증한다.
 * @param name    일정 이름. 필수.
 * @param emotion 이 일정에 연결된 감정 키. 필수. (9종: anxious·depressed·angry·happy·stuck·lethargic·tired·proud·calm)
 */
public record ScheduleItemRequest(
        @Schema(description = "schedules 테이블 일정 id(자유 입력이면 생략)", example = "42") Long id,
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
        return new ScheduleItem(id, name.strip(), tagged);
    }
}
