package com.momentory.retrospect.domain.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** 저장된 신호의 모양이 바뀌어도 예전에 저장된 회고 상태를 읽을 수 있어야 한다. */
class StrategySignalsSnapshotTest {

    @Test
    void readsSignalsSavedByEarlierRuleVersions() {
        // 규칙 v6 까지는 감정 · 사건 · 깊이와 질문 초점도 여기에 저장했다. 지금은 쓰지 않는 값이라 읽고 버린다.
        String saved = """
                {"resistanceCount":1,"resistanceStreak":0,"explicitMode":"LISTEN","emotionOut":true,"eventOut":true,
                 "topicNumber":2,"startDepth":3,"topicDepth":3,"topicStall":1,"wrapUpProposed":true,
                 "lastType":"EXPLORE","lastFocus":"FEELING","lastAsked":true,"turnsSinceQuestion":0,
                 "questionsAsked":4}""";

        StrategySignals signals = JsonMapper.builder().build().readValue(saved, StrategySignals.class);

        assertThat(signals.resistanceCount()).isEqualTo(1);
        assertThat(signals.explicitMode()).isEqualTo(StrategySignals.LISTEN);
        assertThat(signals.topicNumber()).isEqualTo(2);
        assertThat(signals.wrapUpProposed()).isTrue();
        assertThat(signals.questionsAsked()).isEqualTo(4);
        // 그때 없던 값은 비어 있는 것으로 읽는다.
        assertThat(signals.yieldStreak()).isZero();
    }

    @Test
    void readsTheVeryFirstShape() {
        String saved = """
                {"resistanceCount":0,"explicitMode":null,"emotionOut":false,"eventOut":true,"topicDepth":2,
                 "topicStall":0,"lastType":"REFLECT","lastAsked":false,"turnsSinceQuestion":1}""";

        StrategySignals signals = JsonMapper.builder().build().readValue(saved, StrategySignals.class);

        assertThat(signals.topicNumber()).isEqualTo(1);
        assertThat(signals.wrapUpProposed()).isFalse();
        assertThat(signals.questionsAsked()).isZero();
        assertThat(signals.turnsSinceQuestion()).isEqualTo(1);
    }

    @Test
    void roundTripsTheCurrentShape() {
        StrategySignals signals = new StrategySignals(2, 1, StrategySignals.OTHER_VIEW, 2, true,
                StrategyType.REFRAME, 0, 5);
        JsonMapper mapper = JsonMapper.builder().build();

        assertThat(mapper.readValue(mapper.writeValueAsString(signals), StrategySignals.class)).isEqualTo(signals);
    }
}
