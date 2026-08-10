package com.momentory.retrospect.presentation;

import java.util.List;
import java.util.stream.IntStream;

import com.momentory.retrospect.application.StartCommand;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.ScheduleItem;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 회고 시작 요청.
 *
 * @param schedules      오늘의 일정(각각 감정 태그 포함). <b>선택</b> — 비우면 특정 일정 없이
 *                       현재 감정 하나로 '오늘 하루'를 돌아본다. 있으면 각 항목의 이름·감정은 필수.
 * @param currentEmotion 현재 감정 키. <b>필수</b>.
 * @param nickname       호칭 프리픽스에만 쓰인다(사용자 식별과 무관). 선택.
 * @param interest       관심분야. 일정이 여러 개면 선택 규칙에, 일정이 없으면 질문 구체화에 쓰인다. 선택.
 */
public record StartRetrospectRequest(
        @Schema(description = "오늘의 일정 목록(각 항목에 감정 태그). 비우면 '오늘 하루' 회고")
        List<@Valid ScheduleItemRequest> schedules,
        @Schema(description = "현재 감정 키", example = "depressed")
        @NotBlank(message = "currentEmotion은 필수입니다.") String currentEmotion,
        @Schema(description = "호칭 프리픽스(선택)", example = "지은")
        String nickname,
        @Schema(description = "관심분야(선택)", example = "취업 준비")
        String interest) {

    /** @throws InvalidStartException 현재 감정이 없거나, 넣은 일정의 이름·감정 키가 잘못됐으면 */
    StartCommand toDomain() {
        List<ScheduleItemRequest> raw = schedules == null ? List.of() : schedules;
        List<ScheduleItem> items = IntStream.range(0, raw.size())
                .mapToObj(i -> raw.get(i).toDomain(i))
                .toList();
        Emotion current = Emotion.fromKey(currentEmotion)
                .orElseThrow(() -> InvalidStartException.badEmotion("currentEmotion",
                        currentEmotion));

        return new StartCommand(items, current, blankToNull(nickname), blankToNull(interest));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
