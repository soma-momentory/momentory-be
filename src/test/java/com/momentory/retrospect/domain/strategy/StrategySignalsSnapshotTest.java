package com.momentory.retrospect.domain.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** 저장된 신호의 모양이 바뀌어도 예전에 저장된 회고 상태를 읽을 수 있어야 한다. */
class StrategySignalsSnapshotTest {

    @Test
    void readsSignalsSavedBeforeTheWrapUpAndQuestionCountFieldsExisted() {
        String saved = """
                {"resistanceCount":1,"explicitMode":"LISTEN","emotionOut":true,"eventOut":true,"topicDepth":3,
                 "topicStall":1,"lastType":"EXPLORE","lastAsked":true,"turnsSinceQuestion":0}""";

        StrategySignals signals = JsonMapper.builder().build().readValue(saved, StrategySignals.class);

        assertThat(signals.resistanceCount()).isEqualTo(1);
        assertThat(signals.explicitMode()).isEqualTo(StrategySignals.LISTEN);
        assertThat(signals.topicDepth()).isEqualTo(3);
        // 그때 없던 값은 비어 있는 것으로 읽는다.
        assertThat(signals.questionsAsked()).isZero();
        assertThat(signals.startDepth()).isZero();
        assertThat(signals.wrapUpProposed()).isFalse();
        assertThat(signals.lastFocus()).isNull();
        assertThat(signals.topicNumber()).isEqualTo(1);
    }

    @Test
    void roundTripsTheCurrentShape() {
        StrategySignals signals = new StrategySignals(2, 1, StrategySignals.OTHER_VIEW, true, true, 2, 3, 4, 1, true,
                StrategyType.REFRAME, QuestionFocus.EVIDENCE, 0, 5);
        JsonMapper mapper = JsonMapper.builder().build();

        assertThat(mapper.readValue(mapper.writeValueAsString(signals), StrategySignals.class)).isEqualTo(signals);
    }
}
