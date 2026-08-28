package com.momentory.retrospect.domain;

/**
 * 화면에 내보내는 선택지 하나 — 분기점·감정 탐색의 버튼 (채팅흐름_v2).
 *
 * <p>{@code label} 은 표시 문구, {@code description} 은 부연(욕구의 뜻 등, 없으면 null),
 * {@code input} 이 true 면 "직접 적기" 선지라 프론트가 텍스트 입력을 연다. 1-base 번호로 되돌려
 * 받아 {@link RetrospectState#resolveChoice} 로 해석한다.
 */
public record Choice(String label, String description, boolean input) {

    public static Choice of(String label) {
        return new Choice(label, null, false);
    }

    public static Choice of(String label, String description) {
        return new Choice(label, description, false);
    }

    /** "직접 적기" 선지 — 프론트가 텍스트 입력을 받아 content 로 보낸다. */
    public static Choice input(String label) {
        return new Choice(label, null, true);
    }
}
