package com.momentory.retrospect.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.EmotionPhase;
import com.momentory.retrospect.domain.ExtractedEmotion;
import com.momentory.retrospect.domain.ExtractedEvent;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.TopicType;

/**
 * 토픽 파생 검증 (모델 비교 계획 §3.4) — 토픽은 추출 결과(G1)에서 <b>LLM 없이</b> 나온다.
 *
 * <p>예전 G5 콜이 감정을 두 번째로 판단하던 자리라, 여기서 감정이 어떻게 물려지는지가 핵심이다.
 */
class RetrospectTopicDerivationTest {

    private static RetrospectState state() {
        RetrospectState state = new RetrospectState("sess-1");
        state.begin("면접 스터디", null, 77L, "정민", "취업");
        return state;
    }

    private static ExtractedEmotion emotion(Integer eventId, Emotion normalized) {
        return new ExtractedEmotion(eventId, normalized.label(), normalized, 3,
                EmotionPhase.DURING, "그랬어요", List.of(1));
    }

    @Test
    @DisplayName("사건은 짧은 label 로 주 일정 토픽이 되고, 이름이 겹치면 일정 id 로 잇는다")
    void eventBecomesScheduleTopicLinkedBySchedule() {
        RetrospectState state = state();
        state.events(List.of(new ExtractedEvent(1, "면접 스터디", "팀원이 말을 끊었다", List.of(1))));
        state.emotions(List.of(emotion(1, Emotion.ANGRY)));

        List<RetrospectCompleted.TopicData> topics = RetrospectService.topicsFrom(state);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).type()).isEqualTo(TopicType.SCHEDULE);
        assertThat(topics.get(0).label()).isEqualTo("면접 스터디");
        assertThat(topics.get(0).scheduleId()).isEqualTo(77L);
        assertThat(topics.get(0).emotions()).containsExactly(Emotion.ANGRY);
    }

    @Test
    @DisplayName("이름이 안 겹치는 사건은 일정 id 없이 자유 텍스트 토픽으로 남는다")
    void unrelatedEventHasNoScheduleId() {
        RetrospectState state = state();
        state.events(List.of(new ExtractedEvent(1, "친구와 다툼", "저녁에 다퉜다", List.of(2))));

        assertThat(RetrospectService.topicsFrom(state).get(0).scheduleId()).isNull();
    }

    @Test
    @DisplayName("짧은 label 이 없으면 요약을 토픽 이름으로 쓴다")
    void fallsBackToSummaryAsLabel() {
        RetrospectState state = state();
        state.events(List.of(new ExtractedEvent(1, null, "팀원이 말을 끊었다", List.of(1))));

        assertThat(RetrospectService.topicsFrom(state).get(0).label())
                .isEqualTo("팀원이 말을 끊었다");
    }

    @Test
    @DisplayName("사건이 하나면 어디에도 안 붙은 감정까지 그 사건이 물려받는다")
    void singleEventAdoptsUnattributedEmotions() {
        RetrospectState state = state();
        state.events(List.of(new ExtractedEvent(1, "면접 스터디", "말이 막혔다", List.of(1))));
        state.emotions(List.of(emotion(1, Emotion.ANGRY), emotion(null, Emotion.ANXIOUS)));

        assertThat(RetrospectService.topicsFrom(state).get(0).emotions())
                .containsExactly(Emotion.ANGRY, Emotion.ANXIOUS);
    }

    @Test
    @DisplayName("사건이 둘이면 안 붙은 감정은 어느 쪽에도 붙이지 않는다 — 잘못 귀속시키느니 비운다")
    void twoEventsDoNotAdoptUnattributedEmotions() {
        RetrospectState state = state();
        state.events(List.of(
                new ExtractedEvent(1, "면접 스터디", "말이 막혔다", List.of(1)),
                new ExtractedEvent(2, "친구와 다툼", "저녁에 다퉜다", List.of(2))));
        state.emotions(List.of(emotion(1, Emotion.ANXIOUS), emotion(null, Emotion.TIRED)));

        List<RetrospectCompleted.TopicData> topics = RetrospectService.topicsFrom(state);

        assertThat(topics.get(0).emotions()).containsExactly(Emotion.ANXIOUS);
        assertThat(topics.get(1).emotions()).isEmpty();
    }

    @Test
    @DisplayName("사건이 없으면 토픽을 만들지 않는다")
    void noEventsYieldNoTopics() {
        assertThat(RetrospectService.topicsFrom(state())).isEmpty();
    }
}
