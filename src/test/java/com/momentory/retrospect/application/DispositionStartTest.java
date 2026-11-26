package com.momentory.retrospect.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import com.momentory.retrospect.domain.disposition.DispositionArm;
import com.momentory.retrospect.domain.disposition.DispositionAssignment;
import com.momentory.retrospect.domain.disposition.StartingDisposition;
import com.momentory.retrospect.domain.disposition.TurnObservation;
import com.momentory.retrospect.domain.strategy.StrategyPolicy;
import com.momentory.retrospect.domain.strategy.StrategySignals;
import com.momentory.retrospect.infrastructure.persistence.DispositionObservationRecord;
import com.momentory.retrospect.infrastructure.persistence.DispositionObservationRecordRepository;

class DispositionStartTest {
    private final DispositionObservationRecordRepository observations = mock(DispositionObservationRecordRepository.class);
    private final StrategyPolicy policy = new StrategyPolicy(0.5, 0.8, 2, 8);

    private DispositionStart start(DispositionAssignment.Mode mode) {
        return new DispositionStart(observations, policy, mode, Set.of(1L), Set.of(), 5, 0.5);
    }

    private DispositionObservationRecord history() {
        var row = DispositionObservationRecord.start(1L, 10L, 0.5, StrategyPolicy.VERSION);
        row.add(new TurnObservation(false, false, false, false, false, true), StrategyPolicy.VERSION);
        row.add(new TurnObservation(true, true, false, false, true, false), StrategyPolicy.VERSION);
        return row;
    }

    @Test
    void treatmentReadsEveryDimensionAndAppliesOneSignalImmediately() {
        when(observations.findCompletedByUserId(eq(1L), any())).thenReturn(List.of(history()));
        var plan = start(DispositionAssignment.Mode.RANDOM).plan(1L);
        assertThat(plan.arm()).isEqualTo(DispositionArm.TREATMENT);
        assertThat(plan.startingSignals().resistanceCount()).isEqualTo(2);
        assertThat(plan.startingSignals().explicitMode()).isEqualTo(StrategySignals.LISTEN);
        assertThat(plan.startingSignals().gentleEmotion()).isTrue();
        verify(observations).findCompletedByUserId(1L, PageRequest.of(0, 5));
    }

    @Test
    void controlRecordsTheSameDerivedDimensionsButAlwaysStartsFresh() {
        when(observations.findCompletedByUserId(eq(2L), any())).thenReturn(List.of(history()));
        var plan = start(DispositionAssignment.Mode.RANDOM).plan(2L);
        assertThat(plan.arm()).isEqualTo(DispositionArm.CONTROL);
        assertThat(plan.derived()).isEqualTo(new StartingDisposition(true, false, true, true, 1));
        assertThat(plan.startingSignals()).isEqualTo(StrategySignals.initial());
    }

    @Test
    void offDoesNotReadAnotherUsersOrAnyPastObservations() {
        assertThat(start(DispositionAssignment.Mode.OFF).plan(1L).startingSignals())
                .isEqualTo(StrategySignals.initial());
        verifyNoInteractions(observations);
    }

    @Test
    void anExcludedUserStartsFreshWithoutReadingHistoryAndIsLoggedWithoutAnArm() {
        var start = new DispositionStart(observations, policy, DispositionAssignment.Mode.RANDOM, Set.of(), Set.of(1L), 5,
                0.5);
        var plan = start.plan(1L);
        assertThat(plan.arm()).isEqualTo(DispositionArm.NONE);
        assertThat(plan.startingSignals()).isEqualTo(StrategySignals.initial());
        start.open(1L, 20L, plan);
        var saved = org.mockito.ArgumentCaptor.forClass(DispositionObservationRecord.class);
        verify(observations).save(saved.capture());
        verify(observations, org.mockito.Mockito.never()).findCompletedByUserId(any(), any());
        assertThat(saved.getValue().getApplyArm()).isEqualTo(DispositionArm.NONE);
        assertThat(saved.getValue().isPastDispositionApplied()).isFalse();
    }

    @Test
    void aFirstConversationUsesDefaultsEvenInTreatment() {
        when(observations.findCompletedByUserId(eq(1L), any())).thenReturn(List.of());
        assertThat(start(DispositionAssignment.Mode.RANDOM).plan(1L).startingSignals())
                .isEqualTo(StrategySignals.initial());
    }

    @Test
    void aNeutralHistoryIsStillAppliedAndLoggedForTreatment() {
        var row = DispositionObservationRecord.start(1L, 10L, 0.5, StrategyPolicy.VERSION);
        row.add(new TurnObservation(false, false, false, false, false, false), StrategyPolicy.VERSION);
        when(observations.findCompletedByUserId(eq(1L), any())).thenReturn(List.of(row));
        var start = start(DispositionAssignment.Mode.RANDOM);
        var plan = start.plan(1L);
        start.open(1L, 20L, plan);
        var saved = org.mockito.ArgumentCaptor.forClass(DispositionObservationRecord.class);
        verify(observations).save(saved.capture());
        assertThat(saved.getValue().isPastDispositionApplied()).isTrue();
        assertThat(saved.getValue().getDerivedHistorySessions()).isEqualTo(1);
        assertThat(saved.getValue().getDispositionVersion()).isEqualTo(StartingDisposition.VERSION);
    }

    @Test
    void rejectsAnInvalidHistoryWindow() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DispositionStart(observations, policy,
                DispositionAssignment.Mode.RANDOM, Set.of(), Set.of(), 0, 0.5));
    }
}
