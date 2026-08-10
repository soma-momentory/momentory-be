package com.momentory.retrospect.application;

/** 세션이 없거나 다른 사용자의 것이라 접근할 수 없음 — 표현 계층이 404 로 번역한다. */
public final class RetrospectSessionNotFoundException extends RuntimeException {
}
