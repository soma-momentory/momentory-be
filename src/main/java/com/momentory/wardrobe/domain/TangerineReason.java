package com.momentory.wardrobe.domain;

/** 귤이 들고 나는 사유와 보상량. 보상은 사람 · 사유 · 날짜마다 한 번이고, 첫 선물은 평생 한 번이다. */
public enum TangerineReason {
    /** 출석 — 이어 온 날에 따라 값이 다르다({@link Attendance}) */
    CHECK_IN(0),
    DIARY(30),
    SCHEDULES_DONE(10),
    /** 쉼터 한 곳 끝까지 다녀오기(마지막에 기분을 고른 판) */
    REST(10),
    /** 바바의 편지 열어 보기 */
    LETTER(10),
    /** 옷장에 처음 온 날의 선물 */
    WELCOME(100),
    PURCHASE(0);

    private final int amount;

    TangerineReason(int amount) {
        this.amount = amount;
    }

    public int amount() {
        return amount;
    }
}
