package com.momentory.retrospect.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.safety.SafetyLevel;
import com.momentory.retrospect.domain.script.OptionItem;
import com.momentory.retrospect.domain.script.RetroMode;
import com.momentory.retrospect.domain.script.ScriptStep;

class RetrospectStateTest {

    private RetrospectState state() {
        RetrospectState s = new RetrospectState("s1");
        s.begin("면접 스터디", Emotion.ANXIOUS, Emotion.DEPRESSED, "정민");
        return s;
    }

    @Test
    @DisplayName("시작 직후는 intro, 모드를 걸면 script phase 로 간다")
    void beginAndApplyMode() {
        RetrospectState s = state();
        assertThat(s.phase()).isEqualTo(Phase.INTRO);
        assertThat(s.currentStep()).isEmpty();

        s.applyMode(RetroMode.REFRAME);
        assertThat(s.phase()).isEqualTo(Phase.SCRIPT);
        assertThat(s.mode()).isEqualTo(RetroMode.REFRAME);
        // 아직 아무것도 물어보지 않았다.
        assertThat(s.currentStep()).isEmpty();
    }

    @Test
    @DisplayName("advanceStep 이 스크립트를 순서대로 밟고, 끝나면 empty 를 준다")
    void advanceWalksScript() {
        RetrospectState s = state();
        s.applyMode(RetroMode.SHORT_RECORD);

        assertThat(s.advanceStep()).map(ScriptStep::id).contains("intensity");
        assertThat(s.currentStep()).map(ScriptStep::id).contains("intensity");
        assertThat(s.advanceStep()).map(ScriptStep::id).contains("one_line");
        assertThat(s.advanceStep()).isEmpty();
    }

    @Test
    @DisplayName("측정값은 0~10 으로 잘려 저장된다")
    void measureClamped() {
        RetrospectState s = state();
        s.applyMode(RetroMode.SHORT_RECORD);
        s.recordMeasure("intensity", "schedule_emotion", 99);
        s.recordMeasure("intensity", "current_emotion", -3);

        assertThat(s.measures().get("intensity"))
                .containsEntry("schedule_emotion", 10)
                .containsEntry("current_emotion", 0);
    }

    @Test
    @DisplayName("optionId(1-base 번호)로 직전 선택지를 해석한다 — 범위 밖·쓰레기 입력은 empty")
    void resolveOption() {
        RetrospectState s = state();
        s.lastOptions(List.of(OptionItem.of("첫 번째"), OptionItem.of("두 번째")));

        assertThat(s.resolveOption("2")).map(OptionItem::label).contains("두 번째");
        assertThat(s.resolveOption("0")).isEmpty();
        assertThat(s.resolveOption("3")).isEmpty();
        assertThat(s.resolveOption("abc")).isEmpty();
    }

    @Test
    @DisplayName("빈 답변은 기록되지 않는다")
    void blankAnswerIgnored() {
        RetrospectState s = state();
        s.recordAnswer("x", "  ");
        s.recordAnswer("y", null);
        s.recordAnswer("z", " 값 ");

        assertThat(s.answers()).containsOnlyKeys("z");
        assertThat(s.answers().get("z")).isEqualTo("값");
    }

    @Test
    @DisplayName("안전 레벨은 단조 증가 — 올라갈 때만 true 를 돌려준다")
    void safetyMonotonic() {
        RetrospectState s = state();
        assertThat(s.mergeSafety(SafetyLevel.RISK, List.of("crisis_expression"), "m1")).isTrue();
        assertThat(s.mergeSafety(SafetyLevel.NONE, List.of(), "m2")).isFalse();
        assertThat(s.safety().level()).isEqualTo(SafetyLevel.RISK);
    }
}
