package com.momentory.letter.domain;

/**
 * 편지를 어떻게 쓸지 — 생성 전 결정표({@code LetterSafetyGate})가 고른다.
 *
 * <ul>
 *   <li>NORMAL: 지금 편지 그대로(인용 · 맞춤 첫 질문 · 작은 일 세 칸 · 맞춤 제목).</li>
 *   <li>GENTLE: 인용 없음 · 기본 첫 질문 · 작은 일은 낮은 부담 허용 목록 안에서만 · 맞춤 제목 없음 ·
 *       도움받을 곳 한 줄.</li>
 *   <li>QUIET: 생성 모델을 부르지 않는 조용한 편지(지금의 위기 날 편지).</li>
 * </ul>
 */
public enum LetterPolicy {
    NORMAL,
    GENTLE,
    QUIET
}
