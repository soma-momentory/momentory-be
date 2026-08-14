package com.momentory.retrospect.application;

/**
 * 오늘(KST) 이미 회고를 완주해 일기가 남아 있음 — 회고는 하루 한 번뿐이라 새 시작을 막는다.
 * 표현 계층이 409 로 번역한다.
 */
public final class AlreadyRetrospectedTodayException extends RuntimeException {
}
