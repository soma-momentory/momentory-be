package com.momentory.retrospect.domain.assistant;

import java.util.List;

import com.momentory.retrospect.domain.ExtractedTopic;
import com.momentory.retrospect.domain.RetrospectState;

/**
 * 토픽 추출 포트 — 일기 작성 대화 전체에서 주 일정·키워드를 뽑고 각 항목에 감정을 매칭해
 * {@link ExtractedTopic} 목록으로 준다. 일기가 끝나는 시점에 한 번 호출한다(매 턴 아님 — 비용·지연).
 *
 * <p>{@link EmotionExtractor} 와 같은 결이다 — 지금은 LLM 어댑터가 구현하지만 나중에 전용 추출기로
 * 공급자만 교체할 수 있게 경계를 둔다. 실패하면 빈 목록을 준다(던지지 않는다 — 흐름은 계속).
 */
@FunctionalInterface
public interface TopicExtractor {

    List<ExtractedTopic> extract(RetrospectState state);
}
