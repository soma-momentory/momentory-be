package com.momentory.retrospect.domain.assistant;

import java.util.Optional;

import com.momentory.retrospect.domain.RetrospectState;

/**
 * 1턴 답변의 이해 확인 포트 — 어댑터는 infrastructure.ai 에 있다.
 *
 * <p>실패하면 empty 를 돌려준다. 엔진은 고정 폴백 문구로 내려가고 회고는 계속된다.
 */
public interface UnderstandingChecker {

    Optional<UnderstandingCheck> check(RetrospectState state, String firstAnswer);
}
