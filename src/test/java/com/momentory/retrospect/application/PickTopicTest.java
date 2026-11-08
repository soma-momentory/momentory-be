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
 * 일정이 여러 개일 때 1턴 소재를 고르는 우선순위 — 사건성 → 분야별 키워드 → 첫 후보 (나머지는 힌트).
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
    @DisplayName("관심분야 이름 자체는 직접 질문 근거가 아니며 일상 일정은 첫 힌트로 남긴다")
    void interestNameAloneDoesNotForceDirectQuestion() {
        assertThat(pick(List.of(item("아침 운동", true), item("취업 스터디", false)), "취업"))
                .isEqualTo("아침 운동");
    }

    @Test
    @DisplayName("분야 매칭이 없으면 사건성 있는 일정이 일상 일정에 우선한다")
    void eventfulCandidateWinsWhenNoInterestMatch() {
        assertThat(pick(List.of(item("아침 운동", false), item("팀 회의", true)), "취업"))
                .isEqualTo("팀 회의");
    }

    @Test
    @DisplayName("관심분야가 없어도 사건성 있는 일정을 고른다")
    void eventfulCandidateWinsWithoutInterest() {
        assertThat(pick(List.of(item("아침 운동", false), item("팀 회의", true)), null))
                .isEqualTo("팀 회의");
    }

    @Test
    @DisplayName("완료한 일정 사이에서도 사건성 있는 일정이 우선한다")
    void completedRoutineDoesNotBeatEventfulCandidate() {
        assertThat(pick(List.of(item("아침 운동", true), item("팀 회의", true)), null))
                .isEqualTo("팀 회의");
    }

    @Test
    @DisplayName("완료 여부는 사건성 판별의 기준으로 쓰지 않는다")
    void completionDoesNotChangeEventfulness() {
        assertThat(pick(List.of(item("아침 운동", false), item("팀 회의", false)), null))
                .isEqualTo("팀 회의");
    }

    @Test
    @DisplayName("일정이 없으면 '오늘 하루' 회고 — 소재 없음")
    void noSchedules() {
        RetrospectState state = new RetrospectState("s");
        engine.start(state, StartCommand.today("정민", "취업"));

        assertThat(state.schedule()).isNull();
        assertThat(state.phase()).isEqualTo(Phase.DIARY_CHAT);
    }
    @Test
    void fieldKeywordsRankOnlyDirectCandidates() {
        var cases = List.of(
                List.of("학업", "팀 회의", "과제 발표"),
                List.of("취업", "팀 회의", "인턴 면접"),
                List.of("인간관계", "팀 회의", "친구 모임"),
                List.of("건강", "팀 회의", "헬스 모임"));
        for (var c : cases) {
            assertThat(pick(List.of(item(c.get(1), true), item(c.get(2), false)), c.get(0)))
                    .isEqualTo(c.get(2));
        }
    }

    @Test
    void directKeywordsOpenDirectQuestions() {
        for (String title : List.of("약속", "회의", "면접", "발표", "시험", "모임")) {
            var state = new RetrospectState("keyword");
            assertThat(engine.start(state, new StartCommand(List.of(item(title, false)), null, null)).text())
                    .isEqualTo("오늘 " + title + ", 어땠어요?");
            assertThat(state.scheduleHint()).isFalse();
        }
    }

}
