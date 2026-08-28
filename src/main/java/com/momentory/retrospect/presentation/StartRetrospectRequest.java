package com.momentory.retrospect.presentation;

import java.util.List;
import java.util.stream.IntStream;

import com.momentory.retrospect.application.StartCommand;
import com.momentory.retrospect.domain.ScheduleItem;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;

/**
 * 회고 시작 요청 (채팅흐름_v2) — 시작 시 감정을 고르지 않는다.
 *
 * @param schedules 오늘의 일정(각각 감정 태그 포함). <b>선택</b> — 비우면 '오늘 하루' 회고. 서버가 이
 *                  중 대화로 이어갈 개인화 소재 하나를 고른다. 넣은 항목의 이름·감정 키는 유효해야 한다.
 * @param nickname  호칭 프리픽스에만 쓰인다(사용자 식별과 무관). 선택.
 * @param interest  관심분야. 개인화 소재 선택·질문 구체화에 쓰인다. 선택.
 */
public record StartRetrospectRequest(
        @Schema(description = "오늘의 일정 목록(각 항목에 감정 태그). 비우면 '오늘 하루' 회고")
        List<@Valid ScheduleItemRequest> schedules,
        @Schema(description = "호칭 프리픽스(선택)", example = "지은")
        String nickname,
        @Schema(description = "관심분야(선택)", example = "취업 준비")
        String interest) {

    /** @throws InvalidStartException 넣은 일정의 이름·감정 키가 잘못됐으면 */
    StartCommand toDomain() {
        List<ScheduleItemRequest> raw = schedules == null ? List.of() : schedules;
        List<ScheduleItem> items = IntStream.range(0, raw.size())
                .mapToObj(i -> raw.get(i).toDomain(i))
                .toList();
        return new StartCommand(items, blankToNull(nickname), blankToNull(interest));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
