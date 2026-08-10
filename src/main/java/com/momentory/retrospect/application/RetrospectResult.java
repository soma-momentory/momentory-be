package com.momentory.retrospect.application;

/**
 * 회고 시작 결과 — 만들어진 세션 id 와 첫 응답.
 *
 * @param id    이후 턴 요청 경로에 실을 세션 식별자
 * @param reply 첫 메시지(공감 + 1턴 질문)
 */
public record RetrospectResult(Long id, ReplyDto reply) {
}
