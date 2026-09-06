package com.momentory.retrospect.domain.assistant;

import java.util.Optional;

import com.momentory.retrospect.domain.RetrospectState;

/**
 * 일기 작성 채팅 턴 포트 — 어댑터는 infrastructure.ai 에 있다 (채팅흐름_v2 Phase 1).
 *
 * <p>사용자의 방금 답변({@code userText})을 받아 슬롯 추출 + 다음 질문을 {@link DiaryTurn} 으로
 * 한 번에 돌려준다. 실패하면 empty — 엔진이 슬롯별 폴백 질문으로 내려가고 대화는 계속된다.
 */
public interface DiaryChatAssistant {

    Optional<DiaryTurn> turn(RetrospectState state, String userText);
}
