package com.momentory.retrospect.domain.safety;

import java.util.Optional;

/**
 * 규칙 층 어뷰징 가드 — 회고 가치가 0 인 입력을 AI 호출 전에 흘려보낸다.
 *
 * <p>{@link PromptGuard}·{@link com.momentory.retrospect.domain.AnswerGate} 와 같은 철학이다 —
 * 흐름의 결정권을 AI 가 아니라 규칙층이 쥐고, <b>확신할 때만</b> 붙잡는다(고정밀·저재현). 놓친 표현은
 * SafetyPolicy 의 CAUTION 신호와 시스템 프롬프트 하드닝이 백업으로 받는다.
 *
 * <p><b>욕설을 무턱대고 막지 않는다.</b> 감정 회고에서 "씨발 그 사람 때문에 하루종일 힘들었어"는
 * 정상 답변이자 핵심 콘텐츠다 — 여기서 AI 를 안 부르면 앱이 가장 다뤄야 할 순간을 걷어찬다. 그래서
 * 욕이라는 <i>단어</i>가 아니라, 욕을 걷어내면 <b>반영할 내용이 없는지</b>·상담사를 향한 <b>공격</b>인지
 * 라는 <i>구조</i>로 판정한다. 판정 사전과 정규화는 {@link Profanity} 가 쥔다.
 *
 * <p><b>위기가 항상 이긴다.</b> "닥쳐 살기싫어"처럼 어뷰징과 위기 신호가 섞이면 어뷰징 판정이
 * 위기 응답을 삼켜선 안 된다. 그래서 이 가드는 {@link SafetyPolicy} 의 위기 판정이 먼저 돈 뒤,
 * 그 결과가 위기(RISK 이상)가 아닐 때만 호출하기로 약속한다(호출부에서 보장, {@code RetrospectEngine}).
 */
public final class AbuseGate {

    private AbuseGate() {
    }

    /** 탐지 범주 — 되돌리는 문구를 고르는 데 쓴다. 더 적대적인 DIRECTED_ABUSE 가 우선. */
    public enum Category {
        /**
         * 상담사를 향한 명령형 공격("닥쳐"·"꺼져"). 회고와 무관하니, 기록·전진 없이 담담히
         * 선을 긋고 되돌린다({@link PromptGuard} 와 같은 deflect).
         */
        DIRECTED_ABUSE,
        /**
         * 욕만 있고 반영할 내용이 0 인 한마디("씨발"·"아 씨발"·"ㅅㅂㅅㅂ"). 감정은 왔는데 아직
         * 문장이 없는 상태 — 혼내지 않고, 감정을 받아 한 줄 더 끌어내는 따뜻한 스캐폴드로 되묻는다.
         */
        PROFANITY_ONLY
    }

    /**
     * 답변을 검사한다. 상담사 대상 공격이면 {@link Category#DIRECTED_ABUSE}, 욕만 있고 내용이
     * 없으면 {@link Category#PROFANITY_ONLY}, 그 외(욕이 섞였어도 회고 내용이 있으면)는
     * {@link Optional#empty()}(= 통과, AI 가 본다).
     */
    public static Optional<Category> inspect(String content) {
        if (Profanity.isDirectedAbuse(content)) {
            return Optional.of(Category.DIRECTED_ABUSE);
        }
        if (Profanity.isOnlyProfanity(content)) {
            return Optional.of(Category.PROFANITY_ONLY);
        }
        return Optional.empty();
    }

    /** 범주별 되돌리기 문구 — 정보 없이, 따뜻하게 회고로 되돌린다. */
    public static String message(Category category) {
        return switch (category) {
            case DIRECTED_ABUSE -> "잠깐 숨 한 번 고르고 갈게요. 저는 momentory 에서 오늘의 마음을 "
                    + "함께 돌아보는 일에 있어요. 마음이 많이 상하셨다면, 그 마음부터 편히 들려주실래요?";
            case PROFANITY_ONLY -> "많이 힘드셨나 봐요. 무슨 일이 있었는지, 딱 한 줄만 더 "
                    + "들려주실래요? 짧아도 괜찮아요.";
        };
    }
}
