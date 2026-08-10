package com.momentory.retrospect.domain.assistant;

import java.util.Optional;

import com.momentory.retrospect.domain.RetrospectState;

/**
 * 일기 생성 포트 — 어댑터는 infrastructure.ai 에 있다.
 *
 * <p>일기 2종(또는 짧은 기록형은 1종)을 <b>한 번의 호출로</b> 받는다.
 * 실패하면 empty — 엔진이 답변 기록으로 최소한의 일기를 조립한다.
 */
@FunctionalInterface
public interface DiaryWriter {

    Optional<DiaryOutput> write(RetrospectState state);
}
