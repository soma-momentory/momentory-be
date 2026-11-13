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
    void derivesNothingFromASingleConversation() {
        // 하루 컨디션을 성향으로 굳히지 않는다 — 한 대화에서 아무리 뚜렷해도 한 번이면 반영하지 않는다.
        StartingDisposition once = StartingDisposition.of(List.of(talk(5, 3), talk(0, 0)), 2, 2);

        assertThat(once).isEqualTo(StartingDisposition.NONE);
        assertThat(once.isNone()).isTrue();
        assertThat(StartingDisposition.of(List.of(), 2, 2).isNone()).isTrue();
    }

    @Test
    void derivesSelfLedWhenResistanceRepeatedAcrossConversations() {
        StartingDisposition d = StartingDisposition.of(List.of(talk(2, 0), talk(0, 0), talk(3, 0)), 2, 2);

        assertThat(d.selfLed()).isTrue();
        assertThat(d.wantsOtherView()).isFalse();
    }

    @Test
    void aConversationWithLessResistanceThanTheInConversationThresholdDoesNotCount() {
        // 저항이 한 번 나온 대화는 그 대화 안에서도 질문을 줄이지 않았다 — 그런 대화가 여럿이어도 세지 않는다.
        assertThat(StartingDisposition.of(List.of(talk(1, 0), talk(1, 0), talk(1, 0)), 2, 2).selfLed()).isFalse();
    }

    @Test
    void derivesOtherViewFromExplicitRequestsInSeveralConversations() {
        StartingDisposition d = StartingDisposition.of(List.of(talk(0, 1), talk(0, 2)), 2, 2);

        assertThat(d.wantsOtherView()).isTrue();
        assertThat(d.selfLed()).isFalse();
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
}
