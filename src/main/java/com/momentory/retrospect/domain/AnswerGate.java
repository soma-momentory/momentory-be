package com.momentory.retrospect.domain;

import java.util.Optional;
import java.util.Set;

/**
 * 텍스트 답변의 규칙 층 게이트 (STEP 11, Layer 1) — AI 0회, 완전 결정적.
 *
 * <p>"흐름은 규칙, 문구는 AI" 원칙 위에서, 스크립트 진행 전에 <b>거의 항상 비답변인 것</b>만
 * 걸러 되묻는다(빈 답·초단문·정형 회피어). 여기서 못 잡는 미묘한 이탈(질문·딴 얘기)은
 * AI 판정(Layer 2, {@code offTopic})에 넘긴다.
 *
 * <p><b>고정밀·저재현 원칙.</b> 오탐이 진짜 답을 막으면 안 되므로 확신할 때만 HOLD 한다 —
 * 예: {@code ?}·의문사({@code 왜}/{@code 뭐})는 여기서 잡지 않는다("왜 그랬을까 싶었어요"는
 * 진짜 답이다). 그건 문맥을 보는 AI가 판단한다.
 */
public final class AnswerGate {

    private AnswerGate() {
    }

    /** 되묻는 이유 — 폴백 문구 선택에 쓴다. */
    public enum HoldReason {
        /** 빈 답. */
        EMPTY,
        /** 내용이라 보기 어려운 초단문. */
        TOO_SHORT,
        /** 정형화된 회피/비답변 표현. */
        NON_ANSWER,
        /**
         * 답하려 했으나 실질 내용 없이 얼버무린 표현("잘 모르겠는데", "대답하기 어려운데").
         * 비답변보다 부드럽게, 생각의 문턱을 낮추는 발판 멘트로 한 번 더 끌어낸다.
         */
        SOFT_EVASION
    }

    /** 공백 제거 후 정확히 일치하면 비답변으로 본다(부분일치는 오탐이 많아 피한다). */
    private static final Set<String> NON_ANSWERS = Set.of(
            "몰라요", "모르겠어요", "모르겠", "글쎄요", "글쎄", "없어요", "없음", "패스", "그냥", "...");

    /**
     * 얼버무림 전형 — 공백 제거 후 정확일치할 때만 잡는다(고정밀). 여기서 못 잡는 변형("음, 잘
     * 모르겠네요")은 문맥을 보는 Layer 2({@code vague})가 판단한다. "잘 몰라요"처럼 이유·내용이
     * 붙은 진짜 답을 막지 않으려면 표현을 통째로 얼버무린 형태만 시드에 둔다.
     */
    private static final Set<String> SOFT_EVASIONS = Set.of(
            "잘모르겠는데", "잘모르겠어요", "잘모르겠네요", "잘모르겠어", "잘모르겠네", "잘모르겠다",
            "잘모르겠음", "잘모르겠는데요",
            "대답하기어려워요", "대답하기어렵네요", "대답하기어려운데", "대답하기가어려운데",
            "대답하기힘들어요", "대답하기가힘든데",
            "말하기어려워요", "말하기힘들어요", "설명하기어려워요");

    /**
     * 답변을 검사한다. 확신할 때만 HOLD 이유를 돌려주고, 애매하면 {@link Optional#empty()}
     * (= 통과, Layer 2 가 본다).
     */
    public static Optional<HoldReason> inspect(String content) {
        if (content == null || content.isBlank()) {
            return Optional.of(HoldReason.EMPTY);
        }
        String norm = content.strip().replace(" ", "");
        if (norm.length() < 2) {
            return Optional.of(HoldReason.TOO_SHORT);
        }
        if (NON_ANSWERS.contains(norm)) {
            return Optional.of(HoldReason.NON_ANSWER);
        }
        if (SOFT_EVASIONS.contains(norm)) {
            return Optional.of(HoldReason.SOFT_EVASION);
        }
        return Optional.empty();
    }

    /** 이유별 되묻기 문구 — 따뜻한 존댓말, PDF 톤. */
    public static String message(HoldReason reason) {
        return switch (reason) {
            case EMPTY -> "편하게, 떠오르는 대로 한두 줄만 적어주셔도 괜찮아요.";
            case TOO_SHORT -> "조금만 더 들려주실 수 있을까요? 방금 그 순간에 있었던 일 하나면 충분해요.";
            case NON_ANSWER -> "정답은 없어요. 잘 안 떠오르면, 그때 스쳤던 느낌이나 장면 하나만 "
                    + "적어주셔도 돼요.";
            case SOFT_EVASION -> "괜찮아요, 딱 떨어지게 정리하지 않으셔도 돼요. 그때 제일 먼저 "
                    + "떠오른 장면이나, 몸에서 느껴진 것 하나부터 가볍게 말해볼까요?";
        };
    }
}
