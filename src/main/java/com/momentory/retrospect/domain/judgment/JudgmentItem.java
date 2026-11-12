package com.momentory.retrospect.domain.judgment;

import java.util.List;

/**
 * 매 턴 사용자 발화에 대해 판단하는 항목 여섯 가지와 각 항목의 선택지.
 *
 * <p>선택지 키는 판단 모델에 보내는 이름이자 기록에 남는 값이다. 키를 바꾸면 이전 기록과 비교할 수 없게
 * 되므로, 바꿀 때는 질문 버전도 함께 올린다. 모든 항목에 '없음' 또는 '판단 불가' 선택지를 둔다 —
 * 판단할 수 없으면 억지로 고르지 않는다.
 */
public enum JudgmentItem {

    /** 종료 의사 — 현재 대화를 끝내겠다는 표현이 있는가. */
    END_INTENT("end_intent", List.of("end_request", "continue", "unclear")),
    /** 요청한 응답 방식 — 어떤 도움을 명시적으로 요청했는가. */
    REQUESTED_MODE("requested_mode",
            List.of("listen", "explore", "other_view", "advice", "none", "mixed")),
    /** 감정 표현 — 자신의 감정을 직접 표현했는가. */
    EMOTION_EXPRESSION("emotion_expression", List.of("clear", "vague", "none")),
    /** 사건 정보 — 감정과 관련된 사건을 설명했는가. */
    EVENT_INFO("event_info", List.of("specific", "partial", "none")),
    /** 직전 응답에 대한 반응 — 바바의 해석이나 질문에 어떻게 반응했는가. */
    REACTION("reaction", List.of("correct_interpretation", "refuse_question",
            "no_answer_or_topic_change", "argue", "struggle_to_answer", "puzzled_or_objecting",
            "agree_or_elaborate", "undeterminable")),
    /** 체험 깊이 — 말의 초점이 사건·사건에 대한 감정·자기 감정·스스로의 탐색 중 어디에 있는가. */
    EXPERIENCING_DEPTH("experiencing_depth",
            List.of("stage_1_2", "stage_3", "stage_4", "stage_5_plus", "undeterminable"));

    private final String key;
    private final List<String> choices;

    JudgmentItem(String key, List<String> choices) {
        this.key = key;
        this.choices = choices;
    }

    public String key() {
        return key;
    }

    public List<String> choices() {
        return choices;
    }
}
