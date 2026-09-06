package com.momentory.retrospect.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.momentory.retrospect.application.metering.LlmUsageLogger;
import com.momentory.retrospect.domain.Phase;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.ScheduleItem;
import com.momentory.retrospect.domain.safety.SafetyPolicy;

/**
 * 일정이 여러 개일 때 1턴 소재를 고르는 우선순위 — 관심분야 → 끝난 일 → 첫 번째.
 *
 * <p>고른 결과는 {@code state.schedule()} 에 남는다(엔진의 pickTopic 은 private 이라 시작 경로로 잰다).
 */
class PickTopicTest {

    private final FakeAssistant fake = new FakeAssistant();
    private final RetrospectEngine engine = new RetrospectEngine(new SafetyPolicy(), fake, fake,
            fake, fake, new LlmUsageLogger(0.10, 0.40), e -> { }, 1, 3);

    private String pick(List<ScheduleItem> schedules, String interest) {
        RetrospectState state = new RetrospectState("s");
        engine.start(state, new StartCommand(schedules, "정민", interest));
        return state.schedule();
    }

    private static ScheduleItem item(String name, boolean completed) {
        return new ScheduleItem(null, name, null, completed);
    }

    @Test
    @DisplayName("관심분야가 이름에 담긴 일정이 가장 먼저다 — 끝난 일보다도 앞선다")
    void interestWinsOverCompleted() {
        assertThat(pick(List.of(item("아침 운동", true), item("취업 스터디", false)), "취업"))
                .isEqualTo("취업 스터디");
    }

    @Test
    @DisplayName("관심분야가 안 걸리면 끝난 일을 고른다 — 안 한 일은 돌아볼 거리가 없다")
    void completedWinsWhenNoInterestMatch() {
        assertThat(pick(List.of(item("아침 운동", false), item("팀 회의", true)), "취업"))
                .isEqualTo("팀 회의");
    }

    @Test
    @DisplayName("관심분야가 없어도 끝난 일을 고른다")
    void completedWinsWithoutInterest() {
        assertThat(pick(List.of(item("아침 운동", false), item("팀 회의", true)), null))
                .isEqualTo("팀 회의");
    }

    @Test
    @DisplayName("끝난 일이 여럿이면 먼저 나온 것")
    void firstCompletedWins() {
        assertThat(pick(List.of(item("아침 운동", true), item("팀 회의", true)), null))
                .isEqualTo("아침 운동");
    }

    @Test
    @DisplayName("아무 것도 안 끝났으면 첫 번째")
    void fallsBackToFirst() {
        assertThat(pick(List.of(item("아침 운동", false), item("팀 회의", false)), null))
                .isEqualTo("아침 운동");
    }

    @Test
    @DisplayName("일정이 없으면 '오늘 하루' 회고 — 소재 없음")
    void noSchedules() {
        RetrospectState state = new RetrospectState("s");
        engine.start(state, StartCommand.today("정민", "취업"));

        assertThat(state.schedule()).isNull();
        assertThat(state.phase()).isEqualTo(Phase.DIARY_CHAT);
    }
}
