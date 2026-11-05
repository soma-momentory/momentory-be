package com.momentory.report.domain;

/**
 * 그 주를 마무리하는 말투 — 화면의 마지막 인사가 이 값으로 갈린다.
 *
 * <p>위기 안내가 나온 주가 가장 앞선다. 그런 주에는 AI 를 부르지 않고 고정 문장만 쓴다(편지의 조용한
 * 편지와 같은 판단). 평온 · 뿌듯 · 행복을 한 번도 고르지 않은 주는 힘든 주로 보고 「활기차게」를 빼낸다.
 */
public enum WeekTone {
    NORMAL,
    HARD,
    CRISIS
}
