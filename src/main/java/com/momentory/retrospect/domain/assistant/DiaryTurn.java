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
 * <p>종료 판정·다음 슬롯 선택은 서버(엔진)가 슬롯 상태로 결정한다 — 질문은 아직 빈 슬롯을 자연스럽게
 * 채우도록 사용자의 표현을 이어받아 만든다.
 *
 * @param event          이 답변에서 파악한 핵심 사건(중심). 새로 없으면 null.
 * @param secondaryEvents 곁가지로 언급된 사건들(일기 본문에만 가볍게). 없으면 빈 목록.
 * @param meaning        이 답변에서 파악한 '의미'(무엇이 마음에 남았는가). 없으면 null.
 * @param emotionPresent 이 답변에 감정 표현이 담겼는가 — 이른 종료 판정(사건·감정·의미)의 감정 신호.
 *                       정규화(raw→Emotion)는 대화 끝에 {@link EmotionExtractor} 가 한 번에 한다.
 * @param question       다음에 물을 질문 한 문장(공감 1문장 + 질문 1문장 패턴 가능).
 * @param empathy        방금 답변에 대한 짧은 공감 한 문장(질문 없이). 대화를 마무리로 넘길 때 전환
 *                       멘트 앞에 붙인다 — 없으면 null.
 * @param safetyLevel    none|caution|risk|imminent
 * @param noMoreToAsk    다루는 사건(최대 2개)에 대해 더 물어볼 것이 없는가. 서버가 최소 턴을 채운
 *                       뒤 이 신호를 보면 대화를 마무리한다 — 다른 소재로 넓히지 않는다.
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
