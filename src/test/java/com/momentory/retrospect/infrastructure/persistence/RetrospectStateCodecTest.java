package com.momentory.retrospect.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.momentory.retrospect.domain.Choice;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.ExtractedEmotion;
import com.momentory.retrospect.domain.Needs;
import com.momentory.retrospect.domain.Phase;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.safety.SafetyLevel;

import tools.jackson.databind.json.JsonMapper;

/**
 * 스냅샷 코덱 왕복 검증(v2 슬롯) — 진행 상태를 JSON 으로 접었다 펴도 도메인이 동일하게 복원되는가.
 */
class RetrospectStateCodecTest {

    private final RetrospectStateCodec codec = new RetrospectStateCodec(JsonMapper.builder().build());

    @Test
    void roundTripsProgressState() {
        RetrospectState state = new RetrospectState("sess-1");
        state.begin("면접 스터디", Emotion.ANGRY, 77L, "정민", "취업");
        state.event("팀원이 말을 끊었다");
        state.addSecondaryEvents(List.of("점심 약속"));
        state.meaning("계속 걸린다");
        state.markEmotionSeen();
        state.emotions(List.of(new ExtractedEmotion("무시당한 느낌", Emotion.ANGRY, null, null, "끊겼어요")));
        state.bumpDiaryTurn();
        state.diaryDraft("오늘의 일기.");
        state.enterExploration();
        state.bumpExplorationTurn();
        state.confirmEmotions(List.of(Emotion.ANGRY));
        state.chooseNeeds(List.of(Needs.byWord("존중").orElseThrow()));
        state.desiredState("끝까지 들어주는 것");
        state.smallAction("한 문장으로 전해보기");
        state.addUserMessage("사용자 메시지");
        state.mergeSafety(SafetyLevel.CAUTION, List.of("crisis_expression"), "m1");
        state.lastOptions(List.of(Choice.of("보기1", "설명")));

        RetrospectState restored = codec.deserialize(codec.serialize(state));

        assertThat(restored.id()).isEqualTo("sess-1");
        assertThat(restored.nickname()).isEqualTo("정민");
        assertThat(restored.scheduleId()).isEqualTo(77L);
        assertThat(restored.schedule()).isEqualTo("면접 스터디");
        assertThat(restored.scheduleEmotion()).isEqualTo(Emotion.ANGRY);
        assertThat(restored.interest()).isEqualTo("취업");
        assertThat(restored.event()).isEqualTo("팀원이 말을 끊었다");
        assertThat(restored.secondaryEvents()).containsExactly("점심 약속");
        assertThat(restored.meaning()).isEqualTo("계속 걸린다");
        assertThat(restored.emotionSeen()).isTrue();
        assertThat(restored.emotions()).hasSize(1);
        assertThat(restored.emotions().get(0).normalized()).isEqualTo(Emotion.ANGRY);
        assertThat(restored.diaryDraft()).isEqualTo("오늘의 일기.");
        assertThat(restored.phase()).isEqualTo(Phase.EMOTION_EXPLORATION);
        assertThat(restored.explorationEntered()).isTrue();
        assertThat(restored.explorationTurn()).isEqualTo(1);
        assertThat(restored.confirmedEmotions()).containsExactly(Emotion.ANGRY);
        assertThat(restored.needs()).extracting("word").containsExactly("존중");
        assertThat(restored.desiredState()).isEqualTo("끝까지 들어주는 것");
        assertThat(restored.smallAction()).isEqualTo("한 문장으로 전해보기");
        assertThat(restored.messages()).hasSize(1);
        assertThat(restored.safety().level()).isEqualTo(SafetyLevel.CAUTION);
        assertThat(restored.safety().flags()).contains("crisis_expression");
        assertThat(restored.lastOptions()).hasSize(1);
    }

    @Test
    void roundTripsFreshNoScheduleState() {
        RetrospectState state = new RetrospectState("sess-2");
        state.begin(null, null, null, "지은", "취업 준비");

        RetrospectState restored = codec.deserialize(codec.serialize(state));

        assertThat(restored.hasSchedule()).isFalse();
        assertThat(restored.interest()).isEqualTo("취업 준비");
        assertThat(restored.phase()).isEqualTo(Phase.DIARY_CHAT);
    }
}
