package com.momentory.retrospect.domain.assistant;

import java.util.List;

import com.momentory.retrospect.domain.Need;
import com.momentory.retrospect.domain.RetrospectState;

/**
 * 감정 탐색 채팅의 후보 제안 포트 — 어댑터는 infrastructure.ai 에 있다 (채팅흐름_v2 Phase 3).
 *
 * <p>고정 목록/열린 생성 두 결을 나눠 담는다: 바람(욕구)은 {@link com.momentory.retrospect.domain.Needs}
 * 고정 목록에서 맥락에 맞는 것을 고르고(2턴), 작은 행동은 확인한 욕구에 연결된 후보 중 고른다(3턴).
 * 행동은 정본 제목을 그대로 반환하며, 적합한 후보가 없거나 실패하면 빈 목록으로 직접 입력을 허용한다.
 */
public interface ExplorationAssistant {

    /** 2턴 — 고정 욕구 목록에서 대화 맥락에 맞는 3~4개를 고른다(목록 밖 단어는 어댑터가 버린다). */
    List<Need> suggestNeeds(RetrospectState state);

    /** 3턴 — 선택한 사건에 맞는 욕구별 목록의 행동 제목을 최대 3개 반환한다. */
    List<String> suggestActions(RetrospectState state);
}
