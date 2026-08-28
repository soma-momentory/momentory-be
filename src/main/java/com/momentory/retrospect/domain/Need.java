package com.momentory.retrospect.domain;

/**
 * 감정 밑에 있는 '바람(욕구)' 하나 — 고정 단어 + 뜻 (채팅흐름_v2 Phase 3 · 2턴).
 *
 * <p>감정 탐색 2턴에서 대화 맥락에 맞는 3~4개를 {@code "단어 : 뜻"} 형태로 제시한다. 단어 목록과
 * 뜻은 {@link Needs} 에 <b>고정</b>돼 있고(결정 ③), 사용자가 이 중에서 최대 2개를 고르거나 직접
 * 적는다. 추상적 단어만 던지지 않도록 뜻(일상적 설명)을 항상 함께 보여준다.
 */
public record Need(String word, String meaning) {

    /** 화면·프롬프트 표기 — {@code "존중 : 내 의견이 가볍게 다뤄지지 않길 바라는 마음"}. */
    public String toLine() {
        return word + " : " + meaning;
    }
}
