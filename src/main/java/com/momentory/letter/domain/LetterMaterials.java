package com.momentory.letter.domain;

import java.time.LocalDate;
import java.util.List;

import com.momentory.retrospect.domain.Need;

/**
 * 편지 한 통을 쓰는 데 드는 재료(PRD 5-1). 나이 · 성별 · 관심사는 일부러 넣지 않는다 — 편지가
 * 그런 정보로 사람을 단정하지 않게 하기 위해서다.
 *
 * @param userWords        그 회고에서 사용자가 한 말(바바의 말은 뺀다)
 * @param crisis           회고 중 위기 안내가 한 번이라도 나왔는가
 * @param wishNeeds        그 일기의 바람카드에서 확인한 욕구(없으면 빈 목록)
 * @param wishAction       바람카드에서 고른 작은 일(없으면 null)
 * @param todaySchedules   일기 날의 일정 — 「제목 (했음)」 · 「제목 (안 했음)」처럼 체크 여부를 붙인다
 * @param tomorrowSchedules 편지를 받는 날의 일정 제목
 * @param previousAction   전날 편지에서 고른 작은 일과 그 결과(없으면 null)
 */
public record LetterMaterials(Long letterId, LocalDate letterDate, String nickname, String diary,
        List<String> userWords, boolean crisis, List<Need> wishNeeds, String wishAction,
        List<String> todaySchedules, List<String> tomorrowSchedules, String previousAction) {

    public LetterMaterials {
        userWords = List.copyOf(userWords);
        wishNeeds = List.copyOf(wishNeeds);
        todaySchedules = List.copyOf(todaySchedules);
        tomorrowSchedules = List.copyOf(tomorrowSchedules);
    }
}
