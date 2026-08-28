package com.momentory.retrospect.domain.assistant;

import java.util.List;

import com.momentory.retrospect.domain.Need;
import com.momentory.retrospect.domain.RetrospectState;

/**
 * 감정 탐색 채팅의 후보 제안 포트 — 어댑터는 infrastructure.ai 에 있다 (채팅흐름_v2 Phase 3).
 *
 * <p>고정 목록/열린 생성 두 결을 나눠 담는다: 바람(욕구)은 {@link com.momentory.retrospect.domain.Needs}
 * 고정 목록에서 맥락에 맞는 것을 <b>고르고</b>(2턴), 작은 행동은 맥락에 맞게 <b>짧게 만든다</b>(3턴).
 * 실패하면 빈 목록 — 엔진이 폴백(고정 앞자리 욕구 / 일반적 행동)으로 내려간다(던지지 않는다).
 */
public interface ExplorationAssistant {

    /** 2턴 — 고정 욕구 목록에서 대화 맥락에 맞는 3~4개를 고른다(목록 밖 단어는 어댑터가 버린다). */
    List<Need> suggestNeeds(RetrospectState state);

    /** 3턴 — 부담 없이 해볼 수 있는 구체적이고 작은 행동 2~3개를 제안한다. */
    List<String> suggestActions(RetrospectState state);
}
