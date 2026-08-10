package com.momentory.retrospect.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.Phase;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.safety.SafetyLevel;
import com.momentory.retrospect.domain.script.OptionItem;
import com.momentory.retrospect.domain.script.RetroMode;

import tools.jackson.databind.json.JsonMapper;

/**
 * 스냅샷 코덱 왕복 검증 — 진행 상태를 JSON 으로 접었다 펴도 도메인이 동일하게 복원되는가.
 * 특히 파생 필드({@code steps})가 mode 로부터 재도출돼 이어가기가 가능한지 확인한다.
 */
class RetrospectStateCodecTest {

    private final RetrospectStateCodec codec = new RetrospectStateCodec(JsonMapper.builder().build());

    @Test
    void roundTripsProgressState() {
        RetrospectState state = new RetrospectState("sess-1");
        state.begin("면접 스터디", Emotion.ANXIOUS, Emotion.DEPRESSED, "정민");
        state.applyMode(RetroMode.REFRAME);
        state.advanceStep();
        state.recordAnswer("first_moment", "답변을 제대로 못 했어요");
        state.recordMeasure("belief_before", "belief", 7);
        state.addUserMessage("사용자 메시지");
        state.mergeSafety(SafetyLevel.CAUTION, List.of("crisis_expression"), "m1");
        state.situationSummary("면접에서 말이 막힘");
        state.lastOptions(List.of(new OptionItem("보기1", "설명")));
        state.chooseAction(new OptionItem("핵심 세 줄 만들기", "질문 하나를 결론·이유·경험으로"));

        RetrospectState restored = codec.deserialize(codec.serialize(state));

        assertThat(restored.id()).isEqualTo("sess-1");
        assertThat(restored.nickname()).isEqualTo("정민");
        assertThat(restored.schedule()).isEqualTo("면접 스터디");
        assertThat(restored.scheduleEmotion()).isEqualTo(Emotion.ANXIOUS);
        assertThat(restored.currentEmotion()).isEqualTo(Emotion.DEPRESSED);
        assertThat(restored.mode()).isEqualTo(RetroMode.REFRAME);
        assertThat(restored.phase()).isEqualTo(Phase.SCRIPT);
        assertThat(restored.turn()).isEqualTo(state.turn());
        assertThat(restored.answers()).containsEntry("first_moment", "답변을 제대로 못 했어요");
        assertThat(restored.measures().get("belief_before")).containsEntry("belief", 7);
        assertThat(restored.messages()).hasSize(1);
        assertThat(restored.safety().level()).isEqualTo(SafetyLevel.CAUTION);
        assertThat(restored.safety().flags()).contains("crisis_expression");
        assertThat(restored.situationSummary()).isEqualTo("면접에서 말이 막힘");
        assertThat(restored.lastOptions()).hasSize(1);
        assertThat(restored.chosenAction().label()).isEqualTo("핵심 세 줄 만들기");
        // steps 는 저장하지 않지만 mode 로부터 재도출돼 현재 스텝을 그대로 가리킨다.
        assertThat(restored.currentStep()).isEqualTo(state.currentStep());
    }

    @Test
    void roundTripsFreshNoScheduleState() {
        RetrospectState state = new RetrospectState("sess-2");
        state.beginNoSchedule(Emotion.CALM, "지은", "취업 준비");

        RetrospectState restored = codec.deserialize(codec.serialize(state));

        assertThat(restored.hasSchedule()).isFalse();
        assertThat(restored.currentEmotion()).isEqualTo(Emotion.CALM);
        assertThat(restored.interest()).isEqualTo("취업 준비");
        assertThat(restored.phase()).isEqualTo(Phase.INTRO);
        assertThat(restored.mode()).isNull();
    }
}
