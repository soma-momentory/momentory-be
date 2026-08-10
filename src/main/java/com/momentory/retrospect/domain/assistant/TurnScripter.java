package com.momentory.retrospect.domain.assistant;

import java.util.Optional;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.script.ScriptStep;

/**
 * 스크립트 턴 문구 생성 포트 — 어댑터는 infrastructure.ai 에 있다.
 *
 * <p>실패하면 empty. 엔진은 스텝의 폴백 문구(PDF 원형)로 내려가고 회고는 계속된다.
 */
public interface TurnScripter {

    Optional<TurnScript> script(RetrospectState state, ScriptStep step);
}
