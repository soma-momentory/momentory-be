package com.momentory.retrospect.domain.assistant;

import java.util.List;

import com.momentory.retrospect.domain.safety.SafetyLevel;

/**
 * 전략 대화 한 턴의 문장 — 구조화 출력.
 *
 * <p>무엇을 할지(방침)는 서버가 정해서 넘긴다. 모델은 그 방침 안에서 받아주는 말과 질문을 쓰고, <b>대화 전체를 봐야만 알 수
 * 있는 것</b>을 함께 알린다: 방금 답변이 새 이야기인지, 이 이야기에서 더 물을 것이 남았는지.
 *
 * @param ack              방금 답변을 받아주는 말 한두 문장. 질문은 넣지 않는다
 * @param question         이어서 물을 질문 한 문장. 묻지 않는 턴이거나 더 물을 것이 없으면 빈 문자열
 * @param newTopic         방금 답변이 앞에서 다루던 일과 다른 새 이야기를 꺼냈는가
 * @param nothingMoreToAsk 이 이야기에서 더 물을 것이 없는가 — 이미 충분히 들었거나, 사용자가 더 말하기 어려워한다
 * @param topic            지금 다루는 일의 짧은 이름(일기 대체문에 쓴다). 모르면 null
 * @param safetyLevel      none|caution|risk|imminent
 * @param asksFeeling      이번 질문이 그때의 기분이나 마음을 묻는가 — 감정 강도 성향을 재는 데만 쓴다. 모르면 null
 */
public record StrategyReply(String ack, String question, boolean newTopic, boolean nothingMoreToAsk,
        String topic, String safetyLevel, List<String> safetyFlags, Boolean asksFeeling) {

    public StrategyReply {
        safetyFlags = safetyFlags == null ? List.of() : List.copyOf(safetyFlags);
    }

    /** 질문이 기분을 묻는지 알리지 않은 응답. */
    public StrategyReply(String ack, String question, boolean newTopic, boolean nothingMoreToAsk,
            String topic, String safetyLevel, List<String> safetyFlags) {
        this(ack, question, newTopic, nothingMoreToAsk, topic, safetyLevel, safetyFlags, null);
    }

    public boolean questionAsksFeeling() {
        return Boolean.TRUE.equals(asksFeeling);
    }

    public SafetyLevel safetyLevelOrNone() {
        return SafetyLevel.fromKey(safetyLevel);
    }
}
