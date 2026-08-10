package com.momentory.retrospect.application.metering;

/**
 * LLM 호출(또는 질문 풀 대체)이 일어났다는 이벤트.
 *
 * <p>계측은 회고 흐름의 부가 관심사다 — 흐름 제어에는 이벤트를 쓰지 않는다.
 * 이걸 이벤트로 뺀 덕분에 무상태 싱글턴인 LLM 어댑터가 세션별 로그를 들고 있을 필요가 없다
 * (STEP 1 발견 F7 의 계측 쪽 해법).
 */
public record LlmCallRecorded(String sessionId, CallLog log) {
}
