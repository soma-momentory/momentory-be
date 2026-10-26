package com.momentory.retrospect.domain.assistant;

import java.util.List;

/** 후보가 없는 정상 결과와 추천 호출·응답 실패를 구분한다. 외부 API에는 기존 선택지로 표현한다. */
public record ActionSuggestions(List<String> actions, boolean failed) {

    public ActionSuggestions {
        actions = List.copyOf(actions);
        if (failed && !actions.isEmpty()) {
            throw new IllegalArgumentException("추천 실패에는 행동 후보를 담을 수 없습니다.");
        }
    }

    public static ActionSuggestions success(List<String> actions) {
        return new ActionSuggestions(actions, false);
    }

    public static ActionSuggestions failure() {
        return new ActionSuggestions(List.of(), true);
    }
}
