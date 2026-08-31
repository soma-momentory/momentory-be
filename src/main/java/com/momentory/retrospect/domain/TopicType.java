package com.momentory.retrospect.domain;

/**
 * 회고 채팅에서 뽑은 토픽의 종류 (채팅흐름_v2).
 *
 * <ul>
 *   <li>{@code SCHEDULE} — 주 일정. 일정 목록에서 온 것(schedule id 있음)과 채팅에서 뽑은 자유
 *       텍스트(id 없음) 둘 다.</li>
 *   <li>{@code KEYWORD} — 채팅에서 뽑은 키워드. 누적/집계 대상이라 텍스트로만 남는다.</li>
 * </ul>
 *
 * <p>토픽은 회고가 만들어 내는 것이라 분류도 retrospect 도메인이 소유한다. 저장은 이 이름을 문자열로
 * 하는 별도 컨텍스트(retrospecttopic)가 맡는다 — {@link Emotion} 을 diary 가 나눠 쓰는 것과 같은 결.
 */
public enum TopicType {
    SCHEDULE,
    KEYWORD
}
