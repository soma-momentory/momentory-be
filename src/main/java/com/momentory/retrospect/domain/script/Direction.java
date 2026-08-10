package com.momentory.retrospect.domain.script;

import java.util.Optional;

/**
 * 1턴 답변 뒤의 회고 방향 4택 (PDF "회고 방향 선택 UI"). 문구는 고정이다.
 *
 * <p>{@link #PERSPECTIVE}만 세부 2택({@link SubDirection})으로 한 번 더 갈라진다.
 */
public enum Direction {

    EMOTION("emotion", "마음을 조금 더 이야기하며 정리하고 싶어요", "감정 정리형",
            RetroMode.EMOTION_SORTING),
    PERSPECTIVE("perspective", "지금 상황을 다른 관점에서 바라보고 싶어요", "인지 재구성·강점 기반형",
            null),
    PROBLEM("problem", "지금 할 수 있는 방법을 찾아보고 싶어요", "문제 해결형",
            RetroMode.PROBLEM_SOLVING),
    SHORT("short", "오늘 있었던 일을 짧게 기록하고 싶어요", "짧은 기록형",
            RetroMode.SHORT_RECORD);

    private final String key;
    private final String label;
    private final String typeName;
    private final RetroMode mode;

    Direction(String key, String label, String typeName, RetroMode mode) {
        this.key = key;
        this.label = label;
        this.typeName = typeName;
        this.mode = mode;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** 버튼 밑에 작게 보여줄 유형 이름 (PDF의 "→ 감정 정리형" 표기). */
    public String typeName() {
        return typeName;
    }

    /** 이 방향이 바로 확정하는 유형. PERSPECTIVE는 세부 선택이 남아 null. */
    public RetroMode mode() {
        return mode;
    }

    public static Optional<Direction> fromKey(String key) {
        for (Direction d : values()) {
            if (d.key.equals(key)) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }
}
