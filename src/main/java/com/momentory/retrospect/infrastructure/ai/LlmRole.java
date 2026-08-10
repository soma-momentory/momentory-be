package com.momentory.retrospect.infrastructure.ai;

/**
 * AI 호출 역할 — 계측 축.
 *
 * <p>파이프라인은 모델명이 아니라 역할만 지정한다. 지금은 모든 역할이 같은 모델을 쓰지만
 * 계측이 역할별로 쌓이므로 나중에 역할→모델 라우팅을 붙일 수 있다.
 */
public enum LlmRole {

    /** 1턴 답변의 이해 확인(요약 + 상황 한 줄 + 안전 판정). 세션당 1회. */
    G1("AI-G1"),
    /** 스크립트 턴 문구·선택지 생성 + 안전 판정. 텍스트/선택지 턴당 1회. */
    G2("AI-G2"),
    /** AI 없이 템플릿으로 처리한 턴(고정 스크립트·측정·AI 실패 폴백). 호출 0회 — 계측에만 등장. */
    G2_FALLBACK("AI-G2-fallback"),
    /** 일기 생성(그냥+리프레이밍을 한 호출로). 세션당 1회. */
    G4("AI-G4");

    private final String key;

    LlmRole(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
