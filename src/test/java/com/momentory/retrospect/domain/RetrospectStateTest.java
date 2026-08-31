package com.momentory.retrospect.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 회고 세션 애그리거트(v2 슬롯) 검증 — 진입·일기 슬롯·감정 탐색 슬롯·선택지 해석·안전 hold.
 */
class RetrospectStateTest {

    @Test
    @DisplayName("begin — 개인화 소재를 심고 일기 작성에서 시작한다")
    void beginStartsInDiaryChat() {
        RetrospectState s = new RetrospectState("s1");
        s.begin("면접 스터디", Emotion.ANGRY, 7L, "정민", "취업");

        assertThat(s.phase()).isEqualTo(Phase.DIARY_CHAT);
        assertThat(s.hasSchedule()).isTrue();
        assertThat(s.schedule()).isEqualTo("면접 스터디");
        assertThat(s.scheduleId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("일기 슬롯 셋(사건·감정·의미)이 다 차면 종료 조건이 참이다")
    void diarySlotsComplete() {
        RetrospectState s = new RetrospectState("s1");
        s.event("팀원이 말을 끊었다");
        assertThat(s.diarySlotsComplete()).isFalse();
        s.markEmotionSeen();
        assertThat(s.diarySlotsComplete()).isFalse();
        s.meaning("계속 걸린다");
        assertThat(s.diarySlotsComplete()).isTrue();
    }

    @Test
    @DisplayName("일기 턴을 6까지 세면 소진 조건이 참이다")
    void diaryTurnsExhausted() {
        RetrospectState s = new RetrospectState("s1");
        for (int i = 0; i < 5; i++) {
            s.bumpDiaryTurn();
        }
        assertThat(s.diaryTurnsExhausted()).isFalse();
        s.bumpDiaryTurn();
        assertThat(s.diaryTurnsExhausted()).isTrue();
    }

    @Test
    @DisplayName("resolveChoice 는 1-base 번호로 직전 선택지를 해석한다")
    void resolveChoiceByIndex() {
        RetrospectState s = new RetrospectState("s1");
        s.lastOptions(List.of(Choice.of("첫 번째"), Choice.of("두 번째")));

        assertThat(s.resolveChoice("2")).map(Choice::label).contains("두 번째");
        assertThat(s.resolveChoice("9")).isEmpty();
        assertThat(s.resolveChoice("x")).isEmpty();
    }

    @Test
    @DisplayName("감정·바람은 중복을 지우고 최대 2개까지 담긴다(엔진이 자른 뒤 넣는다)")
    void confirmEmotionsDedupe() {
        RetrospectState s = new RetrospectState("s1");
        s.confirmEmotions(List.of(Emotion.ANGRY, Emotion.ANGRY, Emotion.FRUSTRATED));

        assertThat(s.confirmedEmotions()).containsExactly(Emotion.ANGRY, Emotion.FRUSTRATED);
    }

    @Test
    @DisplayName("감정 탐색 진입 — entered 플래그와 phase 를 세운다")
    void enterExploration() {
        RetrospectState s = new RetrospectState("s1");
        s.enterExploration();

        assertThat(s.explorationEntered()).isTrue();
        assertThat(s.phase()).isEqualTo(Phase.EMOTION_EXPLORATION);
        assertThat(s.explorationTurn()).isZero();
    }

    @Test
    @DisplayName("안전 hold → 이어가기는 멈추기 전 phase 로 되돌린다")
    void safetyHoldAndResume() {
        RetrospectState s = new RetrospectState("s1");
        s.enterExploration();
        s.holdForSafety();
        assertThat(s.phase()).isEqualTo(Phase.SAFETY_HOLD);

        Phase resumed = s.resumeFromHold();
        assertThat(resumed).isEqualTo(Phase.EMOTION_EXPLORATION);
        assertThat(s.phase()).isEqualTo(Phase.EMOTION_EXPLORATION);
    }
}
