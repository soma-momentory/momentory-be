package com.momentory.retrospect.domain.assistant;

import java.util.Optional;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.judgment.TurnJudgment;

/**
 * 매 턴 상태 판단 포트 — 어댑터는 infrastructure.ai 에 있다.
 *
 * <p>사용자의 방금 답변({@code userText})과 그 앞의 대화를 보고 여섯 항목을 판단한다. 문장을 만들지
 * 않고 고르기만 한다. 실패하거나 꺼져 있으면 empty — 대화는 판단 없이 그대로 이어진다.
 */
public interface TurnJudge {

    /** 판단하지 않는 구현 — 판단 모델이 없는 환경과 테스트에서 쓴다. */
    TurnJudge NONE = (state, userText) -> Optional.empty();

    Optional<TurnJudgment> judge(RetrospectState state, String userText);
}
