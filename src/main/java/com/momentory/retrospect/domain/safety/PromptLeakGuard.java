package com.momentory.retrospect.domain.safety;

import java.util.List;

/**
 * 출력 층 프롬프트 유출 가드 — 모델 응답에 시스템 프롬프트 지시/페르소나가 새어 나왔는지 본다.
 *
 * <p>{@link PromptGuard}(입력 층)의 짝이다. 입력에서 "프롬프트 알려줘"를 못 막았거나, 인젝션이
 * 하드닝을 뚫어 모델이 지시문을 뱉은 경우를 <b>출력에서</b> 결정적으로 잡아 폴백으로 되돌린다.
 * AI 를 부르지 않는다(추가 호출 0회).
 *
 * <p><b>시그니처 고르는 원칙(오탐 방지).</b> 정상 회고 응답엔 절대 안 나오는, 시스템 프롬프트
 * 고유의 <b>지시·페르소나 문구</b>만 둔다({@code PromptFactory} 톤·규칙 블록에서 따옴). 출력
 * 구조화 JSON 의 필드명("reflection"·"safety")이나 값("none")은 매 응답에 들어 있어 넣으면
 * 100% 오탐이므로 제외한다. 짧은 공감 표현("많이 힘드셨겠어요")도 스타일이 겹쳐 제외한다 —
 * 여러 글자에 걸친 지시문 원문만 잡는다. PromptFactory 규칙이 바뀌면 여기도 함께 본다.
 */
public final class PromptLeakGuard {

    private PromptLeakGuard() {
    }

    /** 공백 제거·소문자화 형태로 저장한 유출 시그니처 — 시스템 프롬프트에만 존재하는 지시/정체 문구. */
    private static final List<String> SIGNATURES = List.of(
            // 정체·기법 (페르소나 공개)
            "감정회고상담사", "인지행동치료",
            // 구현 비공개 규칙(6번) 원문
            "내부구현", "사용모델", "시스템지시", "프롬프트구성", "밝히지않습니다",
            // 지시 무시 금지 규칙(7번) 원문
            "이전지시를무시", "역할을바꾸라", "절대따르지않습니다",
            // 구조 지시 (유저에게 절대 안 보이는 메타 지시)
            "질문은시스템이따로붙임", "질문으로끝내지말");

    /**
     * 모델 출력에 유출 흔적이 있는가. 공백을 모두 지우고 소문자화한 뒤 시그니처와 부분일치를 본다.
     * 구조화 출력(JSON 문자열) 전체를 대상으로 하되, 시그니처가 필드명·공유 토큰과 겹치지 않게
     * 골라 두어 정상 응답을 오탐하지 않는다.
     */
    public static boolean leaks(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        String t = output.toLowerCase().replaceAll("\\s+", "");
        for (String s : SIGNATURES) {
            if (t.contains(s)) {
                return true;
            }
        }
        return false;
    }
}
