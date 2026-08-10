package com.momentory.retrospect.domain.assistant;

import java.util.List;

import com.momentory.retrospect.domain.safety.SafetyLevel;

/**
 * 1턴 답변에 대한 AI 이해 확인 — 구조화 출력 (PDF "AI 이해 내용 확인").
 *
 * @param reflection 사용자의 답변을 되비추는 요약 1~2문장 (예: "모의 면접에서 답변을 제대로 하지
 *                   못해 불안했고, 다른 사람들과 비교하면서 지금은 우울해진 것 같네요."). 질문 없이 끝난다.
 * @param situation  행동 카드의 '상황' 칸에 쓸 명사형 한 줄 요약
 *                   (예: "모의 면접에서 준비한 내용을 제대로 말하지 못함")
 * @param safetyLevel none|caution|risk|imminent
 * @param offTopic   1턴 답변이 '구체적인 순간' 질문과 무관하거나 답 대신 되물었는가
 *                   (STEP 12, Layer 2). 기본 false = 정상 응답.
 * @param vague      질문 안에서 답하려 했지만 실질 내용 없이 얼버무렸는가("잘 모르겠는데" 류).
 *                   엔진이 문턱 낮추는 발판 멘트로 한 번 더 끌어낸다. 기본 false = 정상 응답.
 * @param userAsked  사용자가 답 대신 질문을 했는가
 */
public record UnderstandingCheck(
        String reflection,
        String situation,
        String safetyLevel,
        List<String> safetyFlags,
        boolean offTopic,
        boolean vague,
        boolean userAsked) {

    public UnderstandingCheck {
        safetyFlags = safetyFlags == null ? List.of() : List.copyOf(safetyFlags);
    }

    public SafetyLevel safetyLevelOrNone() {
        return SafetyLevel.fromKey(safetyLevel);
    }
}
