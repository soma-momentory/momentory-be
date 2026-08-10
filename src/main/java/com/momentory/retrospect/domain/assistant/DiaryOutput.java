package com.momentory.retrospect.domain.assistant;

/**
 * 회고 결과 일기 — 구조화 출력.
 *
 * @param diary         그냥 일기. 1인칭 반말, 사실과 감정을 있는 그대로.
 * @param reframedDiary 리프레이밍 일기. 짧은 기록형은 null.
 */
public record DiaryOutput(String diary, String reframedDiary) {

    public boolean hasDiary() {
        return diary != null && !diary.isBlank();
    }
}
