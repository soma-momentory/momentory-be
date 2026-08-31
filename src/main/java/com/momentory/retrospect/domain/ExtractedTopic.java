package com.momentory.retrospect.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 대화에서 뽑은 토픽 한 건 (채팅흐름_v2) — 주 일정({@link TopicType#SCHEDULE}) 또는 키워드
 * ({@link TopicType#KEYWORD})와, 그 항목에 매칭된 감정들(0~여러 개, 중복 제거).
 *
 * <p>일기 작성이 끝나는 시점에 대화 전체를 근거로 한 번 뽑는다({@link
 * com.momentory.retrospect.domain.assistant.TopicExtractor}). 지금은 LLM 어댑터가 만들지만, 나중에
 * 전용 추출기로 공급자만 교체할 수 있게 이 경계 뒤로 분리한다. {@code label} 은 항상 텍스트다.
 */
public record ExtractedTopic(TopicType type, String label, List<Emotion> emotions) {

    public ExtractedTopic {
        emotions = emotions == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(emotions));
    }
}
