package com.momentory.retrospect.domain.assistant;

import java.util.List;

import com.momentory.retrospect.domain.ExtractedEmotion;
import com.momentory.retrospect.domain.ExtractedEvent;

/**
 * {@link EmotionExtractor} 한 번의 결과 — 사건(≤2)·감정·키워드(≤2) (모델 비교 계획 §3.1, §3.4).
 *
 * <p>둘을 한 번에 주는 이유는 {@link ExtractedEmotion#eventId()} 가 {@link ExtractedEvent#id()} 를
 * 참조하기 때문이다 — 따로 뽑으면 두 결과가 서로를 모른 채 어긋난다. 예전에는 감정(G1)과 토픽(G5)이
 * 같은 대화를 서로 모르는 채로 두 번 판단했는데, 그러면 결과가 갈렸을 때 <b>어느 쪽이 정답인지 채점할
 * 수 없다</b>(계획 §2.3-1). 판단은 여기 한 번뿐이고, 토픽 조립은 이 결과에서 파생시킨다.
 *
 * <p>실패하면 {@link #empty()} 를 준다(던지지 않는다 — 흐름은 계속).
 */
public record EmotionExtraction(List<ExtractedEvent> events, List<ExtractedEmotion> emotions) {

    private static final EmotionExtraction EMPTY = new EmotionExtraction(List.of(), List.of());

    public EmotionExtraction {
        events = events == null ? List.of() : List.copyOf(events);
        emotions = emotions == null ? List.of() : List.copyOf(emotions);
    }

    public static EmotionExtraction empty() {
        return EMPTY;
    }
}
