package com.momentory.retrospect.domain.assistant;

import java.util.List;

import com.momentory.retrospect.domain.safety.SafetyLevel;

/**
 * 일기 작성 채팅의 한 턴 결과 — 구조화 출력 (채팅흐름_v2 Phase 1).
 *
 * <p>한 번의 호출로 두 가지를 받는다: ⑴ 사용자의 방금 답변에서 <b>추출한 슬롯</b>(사건·곁가지·의미),
 * ⑵ 다음에 물을 <b>질문</b>. 슬롯은 이 답변에서 새로 파악된 것만 채우고(없으면 null/빈 목록), 감정은
 * 여기서 다루지 않는다({@link EmotionExtractor} 가 대화 끝에 한 번에 뽑는다).
 *
 * <p>종료 판정·소재 전환은 서버(엔진)가 슬롯 상태로 결정한다. 소재 첫 발화에는 공감만 하며,
 * 그 공감 뒤 후속 감정 답변을 받으면 같은 사건의 의미나 생각을 더 묻지 않고 다른 소재로 넘어간다.
 * 의미는 사용자가 자발적으로 말한 것만 추출한다.
 *
 * @param event          이 답변에서 파악한 핵심 사건(중심). 새로 없으면 null.
 * @param secondaryEvents 곁가지로 언급된 사건들(일기 본문에만 가볍게). 없으면 빈 목록.
 * @param meaning        사용자가 자발적으로 말한 사건의 의미. 없으면 null이며 추가 질문으로 채우지 않는다.
 * @param emotionPresent 이 답변에 감정 표현이 담겼는가 — 현재 소재에서 다른 소재로 넘어가는 신호.
 *                       정규화(raw→Emotion)는 대화 끝에 {@link EmotionExtractor} 가 한 번에 한다.
 * @param question       다음에 물을 질문 한 문장(공감 1문장 + 질문 1문장 패턴 가능).
 * @param empathy        방금 답변의 상황·표현한 감정을 짚는 짧은 공감 1~2문장(질문 없이). 소재 첫 발화에는 공감만
 *                       보내고, 후속 감정 답변 뒤에는 소재 전환 질문 앞에 붙인다. 없으면 null.
 * @param safetyLevel    none|caution|risk|imminent
 * @param noMoreToAsk    더 물어볼 것이 없는가. 참고 신호이며 서버의 5턴 고정 종료를 바꾸지 않는다.
 * @param offTopic       답이 질문과 무관하거나 답 대신 되물었는가(Layer 2 게이트). 기본 false.
 * @param vague          답하려 했으나 실질 내용 없이 얼버무렸는가. 기본 false.
 */
public record DiaryTurn(
        String event,
        List<String> secondaryEvents,
        String meaning,
        boolean emotionPresent,
        String question,
        String empathy,
        String safetyLevel,
        List<String> safetyFlags,
        boolean noMoreToAsk,
        boolean offTopic,
        boolean vague) {

    public DiaryTurn {
        secondaryEvents = secondaryEvents == null ? List.of() : List.copyOf(secondaryEvents);
        safetyFlags = safetyFlags == null ? List.of() : List.copyOf(safetyFlags);
    }

    public SafetyLevel safetyLevelOrNone() {
        return SafetyLevel.fromKey(safetyLevel);
    }
}
