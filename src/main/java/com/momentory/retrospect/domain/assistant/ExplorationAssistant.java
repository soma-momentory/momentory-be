package com.momentory.retrospect.domain.assistant;

import java.util.List;

import com.momentory.retrospect.domain.Need;
import com.momentory.retrospect.domain.RetrospectState;

/**
 * 감정 탐색 채팅의 후보 제안 포트 — 어댑터는 infrastructure.ai 에 있다 (채팅흐름_v2 Phase 3).
 *
 * <p>고정 목록/열린 생성 두 결을 나눠 담는다: 바람(욕구)은 {@link com.momentory.retrospect.domain.Needs}
 * 고정 목록에서 맥락에 맞는 것을 고른다(2턴). 작은 행동은 회고에서 고르지 않고 다음 날 아침 바바의
 * 편지에서 고른다(편지 PRD 14-1).
 */
public interface ExplorationAssistant {

    /** 2턴 — 고정 욕구 목록에서 대화 맥락에 맞는 3~4개를 고른다(목록 밖 단어는 어댑터가 버린다). */
    List<Need> suggestNeeds(RetrospectState state);
}
