package com.momentory.weeklyhappiness.domain;

/**
 * 주간 행복의 일곱 칸 — 화면에 놓이는 순서 그대로다(프론트 {@code features/rest/model/weeklyHappiness.ts} 의 key 를 대문자로).
 *
 * <p>좋았던 순간 · 맛있었던 음식 · 좋았던 콘텐츠 · 좋았던 장소 · 고마웠던 사람 · 뿌듯했던 일 · 편안했던 시간.
 */
public enum WeeklyHappinessCategory {
    MOMENT,
    FOOD,
    CONTENT,
    PLACE,
    PERSON,
    PROUD,
    REST
}
