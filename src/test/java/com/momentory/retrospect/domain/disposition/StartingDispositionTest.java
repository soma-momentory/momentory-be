package com.momentory.retrospect.domain.disposition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class StartingDispositionTest {

    private static ConversationSignals talk(int resistance, int otherView) {
        return new ConversationSignals(resistance, otherView);
    }

    @Test
    void afterTheEarlyDaysOneSelfLedConversationIsEnoughButOtherViewMustRepeat() {
        StartingDisposition selfLed = StartingDisposition.of(List.of(talk(2, 0), talk(0, 0)), 1, 2, 2);
        StartingDisposition askedOnce = StartingDisposition.of(List.of(talk(0, 3), talk(0, 0)), 1, 2, 2);
        StartingDisposition askedTwice = StartingDisposition.of(List.of(talk(0, 1), talk(0, 2)), 1, 2, 2);

        assertThat(selfLed).isEqualTo(new StartingDisposition(true, false));
        assertThat(askedOnce).isEqualTo(StartingDisposition.NONE);
        assertThat(askedTwice).isEqualTo(new StartingDisposition(false, true));
    }

    @Test
    void duringTheEarlyDaysOneRequestForAnotherViewIsEnough() {
        assertThat(StartingDisposition.of(List.of(talk(0, 1)), 1, 1, 2))
                .isEqualTo(new StartingDisposition(false, true));
    }

    @Test
    void aConversationWithLessResistanceThanTheInConversationThresholdDoesNotCount() {
        // 저항이 한 번 나온 대화는 그 대화 안에서도 질문을 줄이지 않았다 — 그런 대화가 여럿이어도 세지 않는다.
        assertThat(StartingDisposition.of(List.of(talk(1, 0), talk(1, 0), talk(1, 0)), 1, 2, 2).isNone()).isTrue();
        assertThat(StartingDisposition.of(List.of(), 1, 1, 2).isNone()).isTrue();
    }

    @Test
    void whenBothAppearOnlyTheMoreFrequentDispositionIsDerived() {
        assertThat(StartingDisposition.of(List.of(talk(0, 1), talk(2, 0), talk(3, 0)), 1, 1, 2))
                .isEqualTo(new StartingDisposition(true, false));
        assertThat(StartingDisposition.of(List.of(talk(2, 0), talk(0, 1), talk(0, 1)), 1, 1, 2))
                .isEqualTo(new StartingDisposition(false, true));
    }

    @Test
    void aTieGoesToTheMoreRecentDisposition() {
        assertThat(StartingDisposition.of(List.of(talk(2, 0), talk(0, 1)), 1, 1, 2))
                .isEqualTo(new StartingDisposition(true, false));
        assertThat(StartingDisposition.of(List.of(talk(0, 1), talk(2, 0)), 1, 1, 2))
                .isEqualTo(new StartingDisposition(false, true));
    }

    @Test
    void aTieInTheSameConversationGoesToTheExplicitRequest() {
        assertThat(StartingDisposition.of(List.of(talk(2, 1)), 1, 1, 2))
                .isEqualTo(new StartingDisposition(false, true));
    }

    @Test
    void assignsNobodyWhenOffAndOnlyListedUsersWhenListed() {
        assertThat(new DispositionAssignment(DispositionAssignment.Mode.OFF, Set.of(1L)).armOf(1L))
                .isEqualTo(DispositionArm.NONE);
        DispositionAssignment listed = new DispositionAssignment(DispositionAssignment.Mode.LISTED, Set.of(1L));
        assertThat(listed.armOf(1L)).isEqualTo(DispositionArm.TREATMENT);
        assertThat(listed.armOf(2L)).isEqualTo(DispositionArm.NONE);
    }

    @Test
    void randomAssignmentIsStablePerUserAndSplitsRoughlyInHalf() {
        DispositionAssignment random = new DispositionAssignment(DispositionAssignment.Mode.RANDOM, Set.of());

        long treated = 0;
        for (long id = 1; id <= 1000; id++) {
            DispositionArm arm = random.armOf(id);
            assertThat(arm).isIn(DispositionArm.TREATMENT, DispositionArm.CONTROL);
            assertThat(random.armOf(id)).isEqualTo(arm);
            treated += arm == DispositionArm.TREATMENT ? 1 : 0;
        }
        assertThat(treated).isBetween(400L, 600L);
    }

    @Test
    void randomAssignmentKeepsEachUsersArmDuringTheExperiment() {
        DispositionAssignment random = new DispositionAssignment(DispositionAssignment.Mode.RANDOM, Set.of());

        // 실험 이름이나 해시가 바뀌면 진행 중인 사용자의 집단이 뒤섞인다.
        assertThat(random.armOf(1L)).isEqualTo(DispositionArm.TREATMENT);
        assertThat(random.armOf(2L)).isEqualTo(DispositionArm.CONTROL);
        assertThat(random.armOf(3L)).isEqualTo(DispositionArm.TREATMENT);
        assertThat(random.armOf(4L)).isEqualTo(DispositionArm.TREATMENT);
        assertThat(random.armOf(5L)).isEqualTo(DispositionArm.TREATMENT);
        assertThat(random.armOf(6L)).isEqualTo(DispositionArm.CONTROL);
        assertThat(random.armOf(7L)).isEqualTo(DispositionArm.CONTROL);
        assertThat(random.armOf(8L)).isEqualTo(DispositionArm.CONTROL);
    }

    @Test
    void randomAssignmentDoesNotFollowSignupOrder() {
        DispositionAssignment random = new DispositionAssignment(DispositionAssignment.Mode.RANDOM, Set.of());

        // 가까이 가입한 사용자의 집단이 일정한 주기로 되풀이되면 무작위 배정이 아니다.
        for (int gap = 1; gap <= 8; gap++) {
            long sameArm = 0;
            for (long id = 1; id <= 2000; id++) {
                sameArm += random.armOf(id) == random.armOf(id + gap) ? 1 : 0;
            }
            assertThat(sameArm).as("id 간격 %d", gap).isBetween(880L, 1120L);
        }
    }
}
