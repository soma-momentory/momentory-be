package com.momentory.retrospect.domain;

/**
 * 회고 채팅에서 뽑은 토픽의 종류 (채팅흐름_v2).
 *
 * <ul>
 *   <li>{@code SCHEDULE} — 주 일정. 일정 목록에서 온 것(schedule id 있음)과 채팅에서 뽑은 자유
 *       텍스트(id 없음) 둘 다.</li>
 *   <li>{@code KEYWORD} — <b>더 이상 만들지 않는다.</b> 모델이 사건 이름을 그대로 재사용해
 *       같은 주제가 두 행으로 쌓였다. 사건 label 이 같은 역할을 하므로 생산을 중단했다.
 *       상수는 남긴다 — {@code @Enumerated(EnumType.STRING)} 이라 이미 저장된 행을 읽으려면 필요하다.</li>
 * </ul>
 *
 * <p>토픽은 회고가 만들어 내는 것이라 분류도 retrospect 도메인이 소유한다. 저장은 이 이름을 문자열로
 * 하는 별도 컨텍스트(retrospecttopic)가 맡는다 — {@link Emotion} 을 diary 가 나눠 쓰는 것과 같은 결.
 */
public enum TopicType {
    SCHEDULE,
    KEYWORD
}
