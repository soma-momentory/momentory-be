package com.momentory.retrospect.domain.assistant;

import java.util.Optional;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.strategy.TurnStrategy;

/**
 * 전략 대화의 문장 생성 포트 — 어댑터는 infrastructure.ai 에 있다.
 *
 * <p>서버가 고른 방침({@code strategy})을 받아 그 안에서 받아주는 말과 질문을 쓴다. 실패하면 empty — 대화는 짧은 고정
 * 문구로 이어진다.
 */
public interface StrategyReplyWriter {

    Optional<StrategyReply> write(RetrospectState state, String userText, TurnStrategy strategy);
}
