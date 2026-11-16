package com.momentory.retrospect.infrastructure.ai;

import java.util.LinkedHashMap;
import java.util.Map;

import com.momentory.retrospect.domain.judgment.JudgmentItem;

/**
 * Jev 에 보내는 판단 질문 — 항목별 질문 문장과 선택지 정의.
 *
 * <p>선택지 키는 {@link JudgmentItem#choices()} 와 같아야 한다(테스트가 지킨다). 질문 문장이나 정의를
 * 고치면 {@link #VERSION} 을 올린다 — 판단 기록에 버전이 남아, 고치기 전후의 판단을 섞지 않고 비교할 수 있다.
 */
final class JevJudgmentQuestions {

    /**
     * v2 (2026-11-12): 「직전 응답에 대한 반응」에 '답하려 했지만 어려워함'을 더했다. v1 에서는 "그냥 전부" 같은 답이
     * 갈 곳이 없어 '동의·추가 설명'으로 판단됐다. 안 하겠다(저항)와 못 하겠다(응답 곤란)는 대처가 다르다.
     *
     * <p>v3 (2026-11-12): '의아해하거나 이의를 닮'을 더했다 — 바바의 질문이 과하거나 엉뚱해서 "뭐?" 라고 한 답이 '판단 불가'로
     * 나왔다. '첫 턴' 선택지는 뺐다. 첫 턴인지는 서버가 알고, 이 선택지가 다른 판단의 확률을 깎았다.
     *
     * <p>v4 (2026-11-14): 「일기를 만들어 달라」는 말을 종료 요청에 넣었다. 대화를 마치고 정리하겠다는 뜻인데 종료로 읽히지
     * 않아 바바가 대화를 이어갔다.
     */
    static final String VERSION = "v4";

    private JevJudgmentQuestions() {
    }

    /** Jev 요청의 {@code questions} — 항목 키별 choice 질문. */
    static Map<String, Object> all() {
        Map<String, Object> questions = new LinkedHashMap<>();
        for (JudgmentItem item : JudgmentItem.values()) {
            questions.put(item.key(), Map.of(
                    "type", "choice",
                    "instructions", instructions(item),
                    "criteria", criteria(item)));
        }
        return questions;
    }

    static String instructions(JudgmentItem item) {
        return switch (item) {
            case END_INTENT -> "사용자의 마지막 발화에 지금 이 대화를 끝내겠다는 표현이 있는가?";
            case REQUESTED_MODE -> "사용자가 마지막 발화에서 어떤 도움을 명시적으로 요청했는가? "
                    + "간접 단서로 추론하지 않는다.";
            case EMOTION_EXPRESSION -> "사용자가 마지막 발화에서 자신의 감정을 직접 표현했는가?";
            case EVENT_INFO -> "대화 전체에서 감정과 관련된 사건을 사용자가 설명했는가?";
            case REACTION -> "바바의 직전 응답(해석이나 질문)에 사용자가 어떻게 반응했는가?";
            case EXPERIENCING_DEPTH -> "사용자의 마지막 발화에서 말의 초점이 어디에 있는가?";
        };
    }

    static Map<String, String> criteria(JudgmentItem item) {
        Map<String, String> c = new LinkedHashMap<>();
        switch (item) {
            case END_INTENT -> {
                c.put("end_request", "대화를 끝내거나 그만하겠다고 분명히 말했다. 지금까지의 이야기로 일기를 만들거나 "
                        + "정리해 달라고 한 것도 여기서 마치겠다는 뜻이다");
                c.put("continue", "이야기를 이어가려는 의사가 보인다");
                c.put("unclear", "끝내려는지 알 수 없다. 피곤하다는 말만으로는 종료로 보지 않는다");
            }
            case REQUESTED_MODE -> {
                c.put("listen", "질문이나 조언 없이 그냥 들어달라고 했다");
                c.put("explore", "왜 그런지 함께 알아보고 싶다고 했다");
                c.put("other_view", "자기 생각이 맞는지, 다른 관점이 있는지 물었다");
                c.put("advice", "어떻게 해야 할지 조언을 구했다");
                c.put("none", "명시적인 요청이 없다");
                c.put("mixed", "여러 요청이 섞였거나 불명확하다");
            }
            case EMOTION_EXPRESSION -> {
                c.put("clear", "감정을 분명한 말로 표현했다. 여러 감정을 함께 말해도 여기에 해당한다");
                c.put("vague", "기분을 말했지만 어떤 감정인지 모호하다");
                c.put("none", "사건이나 사실만 말했고 감정 표현이 없다");
            }
            case EVENT_INFO -> {
                c.put("specific", "무슨 일이 있었는지 구체적으로 설명했다");
                c.put("partial", "일부만 말했다");
                c.put("none", "사건 설명이 없다");
            }
            case REACTION -> {
                c.put("correct_interpretation", "바바가 짚은 감정이나 해석을 다른 표현으로 고쳐 말했다");
                c.put("refuse_question", "질문받기를 거부하거나 묻지 말라고 했다");
                c.put("no_answer_or_topic_change", "질문을 받아들이지 않고 답 없이 넘어갔거나 다른 화제로 넘어갔다");
                c.put("argue", "바바의 말이 틀렸다고 반박했다");
                c.put("struggle_to_answer", "질문에 답하려 했지만 무엇이라 말하기 어려워했다('그냥 전부', '모르겠어', "
                        + "'그냥 그래'처럼). 질문을 거부하거나 화제를 바꾼 것은 아니다");
                c.put("puzzled_or_objecting", "바바의 말이 이상하거나 지나치다고 되물었거나 의아해했다('뭐?', "
                        + "'그게 무슨 말이야?', '그 정도는 아닌데'처럼)");
                c.put("agree_or_elaborate", "동의했거나 질문에 답하며 내용을 덧붙였다");
                c.put("undeterminable", "'응'처럼 발화가 너무 짧아 어떤 반응인지 판단할 수 없다");
            }
            case EXPERIENCING_DEPTH -> {
                c.put("stage_1_2", "사건, 타인, 사실만 말한다. 자기 감정 표현이 없다");
                c.put("stage_3", "감정을 말하지만 사건에 대한 반응으로서만 말한다. 초점은 사건이다");
                c.put("stage_4", "자기 감정과 생각 자체가 주제다");
                c.put("stage_5_plus", "자기 내면을 스스로 탐색하거나 새로운 의미를 알아차린다");
                c.put("undeterminable", "발화가 너무 짧아 판단할 수 없다");
            }
        }
        return c;
    }
}
