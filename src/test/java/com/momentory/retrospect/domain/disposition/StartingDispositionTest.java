package com.momentory.retrospect.domain.disposition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class StartingDispositionTest {

    @Test
    void appliesAllThreeDimensionsTogetherFromOneCompletedConversation() {
        var derived = StartingDisposition.of(List.of(new ConversationSignals(1, 1, 0, 0, 1)));
        assertThat(derived).isEqualTo(new StartingDisposition(true, false, true, true, 1));
    }

    @Test
    void oneRequestIsEnoughForAnotherViewRegardlessOfExperimentDay() {
        assertThat(StartingDisposition.of(List.of(new ConversationSignals(0, 0, 1, 1, 0))))
                .isEqualTo(new StartingDisposition(false, true, false, false, 1));
    }

    @Test
    void initiativeAndAnotherViewAreIndependent() {
        assertThat(StartingDisposition.of(List.of(new ConversationSignals(1, 0, 1, 0, 1))))
                .isEqualTo(new StartingDisposition(true, true, false, true, 1));
    }

    @Test
    void balancesSupportRequestsUsingAllRecentConversations() {
        var derived = StartingDisposition.of(List.of(new ConversationSignals(0, 1, 0, 0, 0),
                new ConversationSignals(0, 0, 2, 0, 0)));
        assertThat(derived.wantsOtherView()).isTrue();
        assertThat(derived.wantsListening()).isFalse();
    }

    @Test
    void aSupportTieFollowsTheMostRecentUnequalConversation() {
        var derived = StartingDisposition.of(List.of(new ConversationSignals(0, 1, 0, 0, 0),
                new ConversationSignals(0, 0, 1, 0, 0)));
        assertThat(derived.wantsListening()).isTrue();
        assertThat(derived.wantsOtherView()).isFalse();
    }

    @Test
    void expressionAndAvoidanceAreComparedWithoutTreatingMissingSignalsAsAvoidance() {
        assertThat(StartingDisposition.of(List.of(new ConversationSignals(0, 0, 0, 2, 1))).gentleEmotion()).isFalse();
        assertThat(StartingDisposition.of(List.of(new ConversationSignals(0, 0, 0, 1, 2))).gentleEmotion()).isTrue();
        assertThat(StartingDisposition.of(List.of(new ConversationSignals(0, 0, 0, 1, 1))).gentleEmotion()).isFalse();
    }

    @Test
    void historyWithoutSpecialSignalsStillCountsAsHistoryAndNoHistoryUsesDefaults() {
        assertThat(StartingDisposition.of(List.of())).isEqualTo(StartingDisposition.NONE);
        assertThat(StartingDisposition.of(List.of(new ConversationSignals(0, 0, 0, 0, 0))).historySessions())
                .isEqualTo(1);
    }

    @Test
    void assignsNobodyWhenOffAndOnlyListedUsersWhenListed() {
        assertThat(new DispositionAssignment(DispositionAssignment.Mode.OFF, Set.of(1L), Set.of()).armOf(1L))
                .isEqualTo(DispositionArm.NONE);
        DispositionAssignment listed = new DispositionAssignment(DispositionAssignment.Mode.LISTED, Set.of(1L), Set.of());
        assertThat(listed.armOf(1L)).isEqualTo(DispositionArm.TREATMENT);
        assertThat(listed.armOf(2L)).isEqualTo(DispositionArm.NONE);
    }

    @Test
    void excludedUsersJoinNoArmInAnyModeWhileOthersKeepTheirs() {
        DispositionAssignment random = new DispositionAssignment(DispositionAssignment.Mode.RANDOM, Set.of(), Set.of());
        DispositionAssignment excluding = new DispositionAssignment(DispositionAssignment.Mode.RANDOM, Set.of(), Set.of(1L));
        assertThat(random.armOf(1L)).isEqualTo(DispositionArm.TREATMENT);
        assertThat(excluding.armOf(1L)).isEqualTo(DispositionArm.NONE);
        for (long id = 2; id <= 100; id++) {
            assertThat(excluding.armOf(id)).isEqualTo(random.armOf(id));
        }
        assertThat(new DispositionAssignment(DispositionAssignment.Mode.LISTED, Set.of(1L), Set.of(1L)).armOf(1L))
                .isEqualTo(DispositionArm.NONE);
    }

    @Test
    void randomAssignmentIsStablePerUserAndSplitsRoughlyInHalf() {
        DispositionAssignment random = new DispositionAssignment(DispositionAssignment.Mode.RANDOM, Set.of(), Set.of());

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
        DispositionAssignment random = new DispositionAssignment(DispositionAssignment.Mode.RANDOM, Set.of(), Set.of());

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
        DispositionAssignment random = new DispositionAssignment(DispositionAssignment.Mode.RANDOM, Set.of(), Set.of());

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
