package com.momentory.retrospect.domain.assistant;

import java.util.List;

import com.momentory.retrospect.domain.safety.SafetyLevel;
import com.momentory.retrospect.domain.script.OptionItem;

/**
 * 스크립트 한 턴의 상담사 문구 — 구조화 출력.
 *
 * <p>흐름(무엇을 묻는가)은 {@code ScriptStep} 이 이미 정했다. AI는 사용자의 직전 답변을 엮어
 * 자연스러운 문구를 만들 뿐이다. 선택지 턴이면 {@code options} 도 함께 만든다.
 *
 * @param message 공감 + 질문. 질문은 하나만.
 * @param options 선택지 턴에서만. 아닌 턴은 빈 리스트.
 * @param safetyLevel 사용자의 직전 답변에 대한 안전 판정 (none|caution|risk|imminent)
 * @param offTopic 사용자의 직전 답변이 그 직전 질문과 무관하거나 답 대신 되물었는가
 *                 (STEP 12, Layer 2 — 엔진이 되묻기로 이탈을 완충한다). 기본 false = 정상 응답.
 * @param vague 주제 안에서 답하려 했지만 실질 내용 없이 얼버무렸는가("잘 모르겠는데" 류).
 *              엔진이 문턱 낮추는 발판 멘트로 한 번 더 끌어낸다. 기본 false = 정상 응답.
 * @param userAsked 사용자가 답 대신 질문을 했는가 (모니터링·향후 우회용)
 */
public record TurnScript(
        String message,
        List<OptionItem> options,
        String safetyLevel,
        List<String> safetyFlags,
        boolean offTopic,
        boolean vague,
        boolean userAsked) {

    public TurnScript {
        options = options == null ? List.of() : List.copyOf(options);
        safetyFlags = safetyFlags == null ? List.of() : List.copyOf(safetyFlags);
    }

    public SafetyLevel safetyLevelOrNone() {
        return SafetyLevel.fromKey(safetyLevel);
    }

    public boolean hasMessage() {
        return message != null && !message.isBlank();
    }
}
