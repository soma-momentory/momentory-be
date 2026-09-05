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
 * @param emotion 이 일정에 연결된 감정 키. <b>선택</b> — 채팅흐름_v2 는 대화로 감정을 알아가므로 시작
 *                요청은 일정 이름만 싣는다. 값이 있으면 9종 중 하나여야 한다(있는데 오타면 거부).
 * @param completed 오늘 이 일정을 마쳤는가. <b>선택</b>(생략하면 false) — 서버가 대화 소재를 고를 때
 *                끝난 일을 먼저 본다. v2 는 완료해도 감정을 묻지 않으므로 emotion 으로는 알 수 없다.
 */
public record ScheduleItemRequest(
        @Schema(description = "schedules 테이블 일정 id(자유 입력이면 생략)", example = "42") Long id,
        @Schema(description = "일정 이름", example = "면접 스터디") String name,
        @Schema(description = "이 일정에 연결된 감정 키(선택)", example = "anxious") String emotion,
        @Schema(description = "오늘 이 일정을 마쳤는지(선택, 기본 false)", example = "true")
        Boolean completed) {

    ScheduleItem toDomain(int index) {
        if (name == null || name.isBlank()) {
            throw new InvalidStartException("schedules[%d].name (일정 이름) 은 필수입니다."
                    .formatted(index));
        }
        // 감정은 선택 — 없으면 태그 없이 진행한다. 값이 있는데 유효하지 않을 때만 거부한다.
        Emotion tagged = null;
        if (emotion != null && !emotion.isBlank()) {
            tagged = Emotion.fromKey(emotion)
                    .orElseThrow(() -> InvalidStartException.badEmotion(
                            "schedules[%d].emotion".formatted(index), emotion));
        }
        return new ScheduleItem(id, name.strip(), tagged, Boolean.TRUE.equals(completed));
    }
}
