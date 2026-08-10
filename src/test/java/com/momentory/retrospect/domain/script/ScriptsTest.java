package com.momentory.retrospect.domain.script;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.momentory.retrospect.domain.Emotion;

/** 스크립트 정의가 PDF 시나리오의 턴 구조와 일치하는지 고정한다. */
class ScriptsTest {

    @Test
    @DisplayName("감정 정리형 — 텍스트4 · 선택2(필요한 것 3택, 행동 2택) · 측정1(슬라이더 2개)")
    void emotionSortingScript() {
        List<ScriptStep> steps = Scripts.stepsOf(RetroMode.EMOTION_SORTING);

        assertThat(steps).extracting(ScriptStep::id).containsExactly(
                "peak_moment", "emotion_flow", "body_behavior", "need_now", "self_message",
                "after_intensity", "care_action");
        assertThat(steps).extracting(ScriptStep::kind).containsExactly(
                StepKind.TEXT, StepKind.TEXT, StepKind.TEXT, StepKind.CHOICE, StepKind.TEXT,
                StepKind.MEASURE, StepKind.CHOICE);
        assertThat(byId(steps, "need_now").optionCount()).isEqualTo(3);
        assertThat(byId(steps, "care_action").optionCount()).isEqualTo(2);
        // 감정 정리형은 행동 카드를 만들지 않는다(PDF 산출물: 일기 2종만).
        assertThat(steps).noneMatch(ScriptStep::actionStep);
        assertThat(byId(steps, "after_intensity").measures()).containsExactly(
                MeasureField.SCHEDULE_EMOTION, MeasureField.CURRENT_EMOTION);
        assertThat(RetroMode.EMOTION_SORTING.hasActionCard()).isFalse();
        assertThat(RetroMode.EMOTION_SORTING.hasReframedDiary()).isTrue();
    }

    @Test
    @DisplayName("인지 재구성형 — 전·후 측정에 믿음 슬라이더 포함(3개), 행동 선택이 카드가 된다")
    void reframeScript() {
        List<ScriptStep> steps = Scripts.stepsOf(RetroMode.REFRAME);

        assertThat(steps).extracting(ScriptStep::id).containsExactly(
                "automatic_thought", "belief_before", "evidence_for", "evidence_against",
                "balanced_thought", "belief_after", "verify_action");
        assertThat(byId(steps, "belief_before").measures()).containsExactly(
                MeasureField.BELIEF, MeasureField.SCHEDULE_EMOTION, MeasureField.CURRENT_EMOTION);
        assertThat(byId(steps, "belief_after").measures()).hasSize(3);

        ScriptStep action = byId(steps, "verify_action");
        assertThat(action.actionStep()).isTrue();
        assertThat(action.describedOptions()).isTrue();
        assertThat(action.optionCount()).isEqualTo(2);
        assertThat(RetroMode.REFRAME.hasActionCard()).isTrue();
    }

    @Test
    @DisplayName("강점 기반형 — 강점 3택 · 행동 2택, 측정은 감정 2종")
    void strengthScript() {
        List<ScriptStep> steps = Scripts.stepsOf(RetroMode.STRENGTH);

        assertThat(steps).extracting(ScriptStep::id).containsExactly(
                "difference", "enabling_action", "strength", "strength_change",
                "after_intensity", "apply_action");
        assertThat(byId(steps, "strength").optionCount()).isEqualTo(3);
        assertThat(byId(steps, "apply_action").actionStep()).isTrue();
    }

    @Test
    @DisplayName("문제 해결형 — 행동 선택 뒤에 감정 측정으로 끝난다(PDF 7턴 순서)")
    void problemSolvingScript() {
        List<ScriptStep> steps = Scripts.stepsOf(RetroMode.PROBLEM_SOLVING);

        assertThat(steps).extracting(ScriptStep::id).containsExactly(
                "problem", "desired_outcome", "controllable", "action_purpose", "action",
                "after_intensity");
        assertThat(byId(steps, "action").actionStep()).isTrue();
        assertThat(steps.get(steps.size() - 1).isMeasure()).isTrue();
    }

    @Test
    @DisplayName("짧은 기록형 — 측정 → 한 문장, 리프레이밍 일기 없음")
    void shortRecordScript() {
        List<ScriptStep> steps = Scripts.stepsOf(RetroMode.SHORT_RECORD);

        assertThat(steps).extracting(ScriptStep::id).containsExactly("intensity", "one_line");
        assertThat(steps.get(0).isMeasure()).isTrue();
        assertThat(RetroMode.SHORT_RECORD.hasReframedDiary()).isFalse();
        assertThat(RetroMode.SHORT_RECORD.hasActionCard()).isFalse();
    }

    @Test
    @DisplayName("1턴 공통 질문 — 닉네임·일정·감정 2종이 PDF 문형 그대로 들어간다")
    void firstQuestion() {
        String q = Scripts.firstQuestion("정민", "면접 스터디", Emotion.ANXIOUS,
                Emotion.DEPRESSED);

        // 공감(받아주기)과 질문은 줄바꿈으로 나뉜다.
        assertThat(q).isEqualTo("정민님, 오늘 면접 스터디에서는 불안했고 지금은 우울하다고 했네요.\n"
                + "면접 스터디 중 어떤 일이 있었을 때부터 마음이 무거워지기 시작했나요?");
    }

    @Test
    @DisplayName("닉네임이 없으면 호칭 없이 시작한다")
    void firstQuestionWithoutNickname() {
        String q = Scripts.firstQuestion(null, "산책", Emotion.CALM, Emotion.TIRED);
        assertThat(q).startsWith("오늘 산책에서는 평온했고 지금은 피곤하다고 했네요.");
    }

    @Test
    @DisplayName("일정 감정 = 현재 감정이면 '불안했고 지금은 불안하다' 대신 '지금도 여전히' 문형")
    void firstQuestionWithSameEmotion() {
        String q = Scripts.firstQuestion("지은", "면접 스터디", Emotion.ANXIOUS, Emotion.ANXIOUS);

        assertThat(q).isEqualTo("지은님, 오늘 면접 스터디에서 불안했고, 지금도 여전히 불안하다고 "
                + "했네요.\n면접 스터디 중 어떤 일이 있었을 때부터 마음이 무거워지기 시작했나요?");
        assertThat(q).doesNotContain("불안했고 지금은 불안하다고");
    }

    @Test
    @DisplayName("일정 감정이 긍정이면 '무거워지기' 대신 좋았던 순간을 짚는 문형으로 분기한다")
    void firstQuestionWithPositiveEmotion() {
        String q = Scripts.firstQuestion("정민", "발표", Emotion.HAPPY, Emotion.HAPPY);

        assertThat(q).isEqualTo("정민님, 오늘 발표에서 행복했고, 지금도 여전히 행복하다고 했네요.\n"
                + "발표 중 어떤 순간에 그 기분이 가장 크게 다가왔나요?");
        assertThat(q).doesNotContain("무거워지기");
    }

    @Test
    @DisplayName("이해 확인 폴백도 극성에 맞춰 톤이 갈린다 — 긍정이면 '무거우셨겠어요'가 안 나온다")
    void understandingFallbackByValence() {
        assertThat(Scripts.understandingFallback(Emotion.HAPPY))
                .doesNotContain("무거우셨겠어요");
        assertThat(Scripts.understandingFallback(Emotion.ANXIOUS))
                .contains("무거우셨겠어요");
    }

    @Test
    @DisplayName("템플릿 조사 교정 — 받침에 맞춰 이/가·을/를·과/와·(으)로가 붙는다")
    void josaCorrection() {
        // 불안(받침 O) → 불안이 / 평온(받침 O) → 평온과. 값이 바뀌면 조사도 바뀐다.
        assertThat(Scripts.fill("{감정1이} 커졌다", null, Emotion.ANXIOUS, null, null))
                .isEqualTo("불안이 커졌다");
        assertThat(Scripts.fill("{감정1가} 커졌다", null, Emotion.PROUD, null, null))
                .isEqualTo("뿌듯이 커졌다");
        assertThat(Scripts.fill("{일정을} 마쳤다", "발표", null, null, null))
                .isEqualTo("발표를 마쳤다");
        assertThat(Scripts.fill("{일정을} 마쳤다", "산책", null, null, null))
                .isEqualTo("산책을 마쳤다");
        assertThat(Scripts.fill("{감정2로} 이어졌다", null, null, Emotion.DEPRESSED, null))
                .isEqualTo("우울로 이어졌다");
        assertThat(Scripts.fill("{감정2로} 이어졌다", null, null, Emotion.LETHARGIC, null))
                .isEqualTo("무기력으로 이어졌다");
    }

    @Test
    @DisplayName("믿음 슬라이더 라벨 — 사전 측정은 생각을 인용하고, 사후 측정은 '처음 생각'으로 부른다")
    void beliefLabels() {
        String before = Scripts.measureLabel(MeasureField.BELIEF, "면접 스터디",
                Emotion.ANXIOUS, Emotion.DEPRESSED, "나는 부족하다", false);
        String after = Scripts.measureLabel(MeasureField.BELIEF, "면접 스터디",
                Emotion.ANXIOUS, Emotion.DEPRESSED, "나는 부족하다", true);

        assertThat(before).contains("나는 부족하다");
        assertThat(after).isEqualTo("처음 생각에 대한 믿음");
    }

    @Test
    @DisplayName("감정 슬라이더 라벨 — 일정 감정/현재 감정이 구분된다 (PDF 짧은 기록형 라벨)")
    void emotionLabels() {
        assertThat(Scripts.measureLabel(MeasureField.SCHEDULE_EMOTION, "면접 스터디",
                Emotion.ANXIOUS, Emotion.DEPRESSED, null, false))
                .isEqualTo("면접 스터디에서 느낀 불안");
        assertThat(Scripts.measureLabel(MeasureField.CURRENT_EMOTION, "면접 스터디",
                Emotion.ANXIOUS, Emotion.DEPRESSED, null, false))
                .isEqualTo("지금 느끼는 우울");
    }

    private static ScriptStep byId(List<ScriptStep> steps, String id) {
        return steps.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }
}
