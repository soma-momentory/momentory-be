package com.momentory.retrospect.domain.script;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.momentory.retrospect.domain.Emotion;

/**
 * 모드별 고정 턴 스크립트 (채팅 최적 시나리오 PDF의 흐름을 코드로 옮긴 것).
 *
 * <p>흐름은 전부 여기서 결정된다 — AI는 각 턴의 {@code intent}에 맞춰 문구를 다듬을 뿐,
 * 순서·형태(텍스트/선택지/슬라이더)를 바꾸지 못한다. 폴백 문구는 PDF의 원형 질문이라
 * AI가 실패해도 시나리오 그대로의 문장이 나간다.
 *
 * <p>템플릿 토큰: {@code {일정}} {@code {감정1}}(일정에 연결된 감정 어근) {@code {감정2}}(현재 감정 어근)
 * {@code {생각}}(자동적 사고). 토큰 뒤에 조사를 붙인 형태({@code {일정을}} 등)는 받침에 맞춰 교정된다.
 */
public final class Scripts {

    private Scripts() {
    }

    // ── 공통 진입 ────────────────────────────────────────────────────────

    /**
     * 1턴 — 구체적인 순간 (모든 유형 공통, 템플릿이라 AI 0회).
     *
     * <p>일정 감정과 현재 감정이 같으면 "지금은 불안하다고 했네요"가 어색해지므로
     * "지금도 여전히 ~" 문형으로 바꾼다.
     *
     * <p>질문 줄은 일정 감정의 극성에 맞춰 분기한다 — 긍정(행복·뿌듯·평온)인데 "마음이
     * 무거워지기 시작했나요"로 물으면 맥락이 어긋나므로, 그때는 좋았던 순간을 짚는 문형으로 바꾼다.
     */
    public static String firstQuestion(String nickname, String schedule, Emotion scheduleEmotion,
            Emotion currentEmotion) {
        String prefix = nickname == null || nickname.isBlank() ? "" : nickname + "님, ";
        String lead = scheduleEmotion == currentEmotion
                ? "오늘 {일정}에서 {감정1}했고, 지금도 여전히 {감정1}하다고 했네요."
                : "오늘 {일정}에서는 {감정1}했고 지금은 {감정2}하다고 했네요.";
        boolean positive = scheduleEmotion != null && scheduleEmotion.isPositive();
        String question = positive
                ? "{일정} 중 어떤 순간에 그 기분이 가장 크게 다가왔나요?"
                : "{일정} 중 어떤 일이 있었을 때부터 마음이 무거워지기 시작했나요?";
        // 공감(받아주기)과 질문은 줄을 나눈다 — 붙여 쓰면 읽기 힘들다는 피드백 반영.
        return fill(prefix + lead + "\n" + question,
                schedule, scheduleEmotion, currentEmotion, null);
    }

    /**
     * 특정 일정 없이 시작할 때의 1턴 질문 — 현재 감정 하나로 '오늘 하루'를 연다(템플릿, AI 0회).
     * 온보딩 관심분야가 있으면 질문을 그 쪽으로 구체화한다.
     */
    public static String firstQuestionNoSchedule(String nickname, Emotion currentEmotion,
            String interest) {
        String prefix = nickname == null || nickname.isBlank() ? "" : nickname + "님, ";
        String emo = currentEmotion == null ? "복잡" : currentEmotion.stem();
        String lead = prefix + "오늘 하루, " + emo + "한 마음이 남아 있다고 했네요.";
        String question = interest != null && !interest.isBlank()
                ? "혹시 " + interest + Josa.pick(interest, "과", "와")
                        + " 관련된 일인가요? 어떤 순간에 그런 마음이 들기 시작했나요?"
                : "오늘 하루 중 어떤 순간에 그런 마음이 들기 시작했나요?";
        // 공감과 질문은 줄을 나눈다.
        return lead + "\n" + question;
    }

    /**
     * 일정 없는 회고의 측정 턴 문구 — 현재 감정 하나(인지 재구성형은 믿음 포함)만 묻는다.
     * 일정 감정 슬라이더가 빠지므로 두 감정을 언급하는 기본 문구 대신 이걸 쓴다.
     */
    public static String measurePromptSingleEmotion(Emotion currentEmotion, boolean withBelief) {
        String emo = currentEmotion == null ? "복잡" : currentEmotion.stem();
        String body = withBelief
                ? "그 생각이 지금 얼마나 사실처럼 느껴지는지와, 지금 " + emo + "한 마음의 정도를 표시해 주세요."
                : "지금 " + emo + "한 마음이 어느 정도인지 표시해 주세요.";
        return body + SCALE_HINT;
    }

    /** 일정이 여러 개인데 규칙으로 하나를 못 고를 때 — 사용자에게 선택권을 주는 질문. */
    public static String scheduleChoiceQuestion(String nickname) {
        String prefix = nickname == null || nickname.isBlank() ? "" : nickname + "님, ";
        return prefix + "오늘은 여러 일정이 있었네요. 어떤 일정에 대해 이야기해 볼까요?";
    }

    /** 이해 확인 요약 뒤에 붙는 고정 질문 (PDF "AI 이해 내용 확인 및 회고 방향 선택"). */
    public static final String DIRECTION_QUESTION =
            "지금은 어떤 방향으로 이어가고 싶나요?";

    /**
     * 이해 확인 AI가 실패했을 때의 폴백 공감문 — 현재 감정 극성에 맞춰 톤을 고른다.
     * 긍정 감정인데 "마음이 무거우셨겠어요"로 받으면 어긋나므로 좋은 순간을 함께 기뻐하는 문형으로 바꾼다.
     */
    public static String understandingFallback(Emotion currentEmotion) {
        boolean positive = currentEmotion != null && currentEmotion.isPositive();
        return positive
                ? "이야기해 주셔서 고마워요. 오늘 그런 순간이 있었다니 참 좋네요."
                : "이야기해 주셔서 고마워요. 마음이 많이 무거우셨겠어요.";
    }

    /** 세부 방향 질문 (다른 관점 선택 시, 템플릿이라 AI 0회). */
    public static String subDirectionQuestion(String nickname) {
        return nickname == null || nickname.isBlank()
                ? "지금의 경험에는 어떤 방향이 더 가까운가요?"
                : "지금 " + nickname + "님의 경험에는 어떤 방향이 더 가까운가요?";
    }

    // ── 모드별 스크립트 ──────────────────────────────────────────────────

    private static final String SCALE_HINT =
            " 0은 거의 느껴지지 않는 상태이고, 10은 매우 강하게 느껴지는 상태예요.";

    /** 대화 후 측정(after) 문구 끝에 붙는 기준선 안내 — 슬라이더 시작값이 그 당시 감정임을 밝힌다. */
    private static final String BASELINE_NOTE = " (5는 그 당시에 느꼈던 감정이에요)";

    private static final Map<RetroMode, List<ScriptStep>> STEPS = Map.of(

            // 1. 감정 정리형 — 감정을 충분히 알아주고 자기 위로로 마무리.
            RetroMode.EMOTION_SORTING, List.of(
                    ScriptStep.text("peak_moment",
                            "사건의 흐름 안에서 감정({감정1})이 가장 크게 올라왔던 장면이 언제였는지 묻는다. "
                                    + "직전 답변에 나온 사건을 짚으며 '그 순간부터 끝날 때까지' 범위를 준다.",
                            "그 순간부터 {일정이} 끝날 때까지, {감정1이} 가장 크게 올라왔던 장면은 언제였나요?"),
                    ScriptStep.text("emotion_flow",
                            "일정에서 느낀 감정({감정1})이 지금의 감정({감정2})으로 이어지기까지 어떤 일이 "
                                    + "있었는지, 일정이 끝난 뒤부터 차례대로 이야기하게 한다.",
                            "{일정}에서 느낀 {감정1이} 지금의 {감정2로} 이어지기까지 어떤 일이 있었는지, "
                                    + "{일정이} 끝난 뒤부터 차례대로 이야기해 볼까요?"),
                    ScriptStep.text("body_behavior",
                            "지금 감정({감정2})이 몸에서 어떻게 느껴지는지, 그리고 그 뒤에 무엇을 하거나 "
                                    + "하지 못했는지 한 번에 묻는다.",
                            "지금 {감정2}한 마음은 몸에서 어떻게 느껴지나요? 그리고 그 뒤에는 무엇을 하거나 "
                                    + "하지 못했나요?"),
                    ScriptStep.choice("need_now",
                            "지금 사용자에게 가장 필요한 것이 무엇과 가까운지 3가지 보기로 묻는다. "
                                    + "보기는 대화 맥락을 반영해 서로 결이 다르게 — 예: 상황을 바로잡을 수 있다는 확신 / "
                                    + "힘들었던 마음을 충분히 알아주는 것 / 비교나 평가 없이 잠시 쉬는 시간.",
                            "지금 가장 필요한 것은 무엇과 가까울까요?",
                            List.of(OptionItem.of("지금 상황을 바로잡을 수 있다는 확신"),
                                    OptionItem.of("오늘 힘들었던 마음을 충분히 알아주는 것"),
                                    OptionItem.of("잠시 벗어나 쉴 수 있는 시간"))),
                    ScriptStep.text("self_message",
                            "오늘 힘들었던 자신에게, 평가하거나 조언하지 않고 건넬 한마디를 묻는다.",
                            "오늘 {감정1}하고 {감정2}했던 자신에게, 평가하거나 조언하지 않고 한마디를 "
                                    + "건넨다면 뭐라고 말해주고 싶나요?"),
                    ScriptStep.measure("after_intensity",
                            "오늘의 대화를 통해, {일정}에서 느낀 {감정1과} 오늘의 감정 {감정2가} "
                                    + "지금은 어느 정도로 느껴지는지 표시해 주세요."
                                    + SCALE_HINT + BASELINE_NOTE,
                            MeasureField.SCHEDULE_EMOTION, MeasureField.CURRENT_EMOTION),
                    ScriptStep.choice("care_action",
                            "남아 있는 감정을 돌보기 위해 오늘 바로 할 수 있는 아주 작은 행동 2가지를 "
                                    + "제안한다. 대화에 나온 내용을 반영하되 부담 없고 구체적으로.",
                            "지금 남아 있는 {감정1과} {감정2를} 돌보기 위해, 더 필요한 행동을 하나 골라볼까요?",
                            List.of(OptionItem.of("따뜻한 물을 마시며 10분 동안 편하게 쉬기"),
                                    OptionItem.of("마음에 걸리는 일은 덮어두고 내일 확인할 시간만 정해두기")))),

            // 2. 인지 재구성형 — 자동적 사고 검증(전·후 믿음 측정).
            RetroMode.REFRAME, List.of(
                    ScriptStep.text("automatic_thought",
                            "그 순간 속으로 어떤 생각이 떠올랐는지(자동적 사고) 묻는다. 정확히 기억나지 "
                                    + "않아도 그때 마음에 가장 가까운 말을 적어달라고 덧붙인다.",
                            "그 순간, 속으로 어떤 생각을 했나요? 정확히 기억나지 않아도 그때 마음에 가장 "
                                    + "가까운 말을 적어주세요."),
                    ScriptStep.measure("belief_before",
                            "그 생각이 얼마나 사실처럼 느껴졌는지와 {감정1과} {감정2}의 정도를 표시해 주세요."
                                    + SCALE_HINT,
                            MeasureField.BELIEF, MeasureField.SCHEDULE_EMOTION,
                            MeasureField.CURRENT_EMOTION),
                    ScriptStep.text("evidence_for",
                            "그 생각이 사실처럼 느껴진 이유를 오늘 있었던 일에서 찾게 한다. 느낌이나 예상은 "
                                    + "빼고 실제로 있었던 말과 행동만 이야기하게 한다.",
                            "그 생각이 사실처럼 느껴진 이유를 오늘 있었던 일에서 찾아볼까요? 느낌이나 예상은 "
                                    + "잠시 빼고, 실제로 어떤 말과 행동이 있었는지 이야기해 주세요."),
                    ScriptStep.text("evidence_against",
                            "반대로 그 생각이 전부 사실은 아닐 수도 있음을 보여주는 경험이나 사실을 묻는다. "
                                    + "작은 내용이어도 괜찮다고 덧붙인다.",
                            "반대로, 그 생각이 전부 사실은 아닐 수도 있다는 걸 보여주는 경험이나 사실도 "
                                    + "있을까요? 작은 내용이어도 괜찮아요."),
                    ScriptStep.text("balanced_thought",
                            "지금까지 확인한 증거를 모두 반영해 처음 생각을 다시 표현하게 한다. 억지로 좋게 "
                                    + "바꾸지 말고 지금 믿을 수 있는 정도로 적게 한다.",
                            "지금까지 확인한 내용을 모두 반영하면, 처음 생각을 어떻게 다시 표현할 수 "
                                    + "있을까요? 억지로 좋게 바꾸지 말고 지금 믿을 수 있는 정도로 적어주세요."),
                    ScriptStep.measure("belief_after",
                            "오늘의 대화를 통해, 처음 생각에 대한 믿음과 {일정}에서 느낀 {감정1}, "
                                    + "오늘의 감정 {감정2가} 지금은 각각 어느 정도로 느껴지는지 "
                                    + "표시해 주세요." + SCALE_HINT + BASELINE_NOTE,
                            MeasureField.BELIEF, MeasureField.SCHEDULE_EMOTION,
                            MeasureField.CURRENT_EMOTION),
                    ScriptStep.action("verify_action",
                            "방금 정리한 균형 잡힌 생각을 실제로 확인해 볼 수 있는 아주 작은 행동 2가지를 "
                                    + "제안한다. 각 보기는 제목과 한 줄 설명으로.",
                            "방금 정리한 생각을 실제로 확인해 볼 수 있는 행동을 하나 골라볼까요?",
                            List.of(new OptionItem("오늘 일을 다시 정리해 보기",
                                            "오늘 있었던 일 하나를 골라 핵심을 세 줄로 정리한 뒤 소리 내어 말해보기"),
                                    new OptionItem("이전과 비교해 보기",
                                            "예전의 비슷한 경험과 오늘을 비교해 나아진 점 한 가지 찾기")))),

            // 3. 강점 기반형 — 이전과 다르게 해낸 점에서 강점 발견.
            RetroMode.STRENGTH, List.of(
                    ScriptStep.text("difference",
                            "이전의 비슷한 상황과 비교했을 때 오늘 조금이라도 다르게 해낸 부분이 있었는지 묻는다.",
                            "이전의 비슷한 상황과 비교했을 때, 오늘 조금이라도 다르게 해낸 부분이 있었나요?"),
                    ScriptStep.text("enabling_action",
                            "그렇게 할 수 있었던 건 어떤 행동을 했기 때문인지 — 다르게 해낸 것을 가능하게 한 "
                                    + "구체적 행동을 묻는다.",
                            "그렇게 할 수 있었던 건 어떤 행동을 했기 때문일까요?"),
                    ScriptStep.choice("strength",
                            "그 행동에서 드러난 강점이 무엇과 가장 가까운지 3가지 보기로 묻는다. 보기는 대화 "
                                    + "맥락을 반영해 서로 결이 다르게 — 예: 다시 이어가는 회복력 / 배우려는 태도 / "
                                    + "끝까지 해내는 책임감.",
                            "그 행동에서 드러난 강점은 무엇과 가장 가까울까요?",
                            List.of(OptionItem.of("긴장해도 다시 이어가는 힘"),
                                    OptionItem.of("부족한 부분을 인정하고 배우려는 태도"),
                                    OptionItem.of("어려워도 맡은 일을 끝까지 해내는 책임감"))),
                    ScriptStep.text("strength_change",
                            "그 강점을 사용한 덕분에 오늘은 이전과 무엇이 달라졌는지 묻는다.",
                            "그 강점을 사용한 덕분에 오늘은 이전과 무엇이 달라졌나요?"),
                    ScriptStep.measure("after_intensity",
                            "오늘의 대화를 통해, {일정}에서 느낀 {감정1과} 오늘의 감정 {감정2가} "
                                    + "지금은 어느 정도로 느껴지는지 표시해 주세요."
                                    + SCALE_HINT + BASELINE_NOTE,
                            MeasureField.SCHEDULE_EMOTION, MeasureField.CURRENT_EMOTION),
                    ScriptStep.action("apply_action",
                            "오늘 발견한 강점을 다음 비슷한 상황에서도 활용할 수 있는 행동 2가지를 제안한다. "
                                    + "각 보기는 제목과 한 줄 설명으로.",
                            "오늘 발견한 강점을 다음에도 활용할 수 있도록, 한 번 실천해 볼 행동을 골라볼까요?",
                            List.of(new OptionItem("막혀도 다시 이어가기",
                                            "막히면 잠깐 숨을 고르고 기억나는 핵심 한 가지부터 끝까지 해보기"),
                                    new OptionItem("달라진 점 기록하기",
                                            "비슷한 일이 끝난 뒤 이전보다 나아진 행동을 한 가지 적기")))),

            // 4. 문제 해결형 — 통제 가능한 부분에서 첫 행동 정하기.
            RetroMode.PROBLEM_SOLVING, List.of(
                    ScriptStep.text("problem",
                            "오늘 있었던 일에서 가장 먼저 해결하고 싶은 문제를 묻는다. 막연한 목표보다 지금 "
                                    + "바꾸고 싶은 부분을 구체적으로 말하게 한다.",
                            "오늘 있었던 일에서 가장 먼저 해결하고 싶은 문제는 무엇인가요? 막연한 목표보다 "
                                    + "지금 바꾸고 싶은 부분을 구체적으로 말해 주세요."),
                    ScriptStep.text("desired_outcome",
                            "다음 비슷한 상황에서는 무엇이 달라지면 좋을지 묻는다. 완벽한 모습보다 현실적으로 "
                                    + "바라는 모습을 이야기하게 한다.",
                            "다음에는 지금과 비교해 무엇이 달라지면 좋을까요? 완벽하게 해내는 것보다 "
                                    + "현실적으로 바라는 모습을 이야기해 주세요."),
                    ScriptStep.text("controllable",
                            "다른 사람의 반응·평가처럼 바꾸기 어려운 부분은 잠시 빼고, 사용자가 직접 준비하거나 "
                                    + "연습할 수 있는 부분이 무엇인지 묻는다.",
                            "바꾸기 어려운 부분은 잠시 빼볼게요. 지금 직접 준비하거나 연습할 수 있는 부분은 "
                                    + "무엇일까요?"),
                    ScriptStep.choice("action_purpose",
                            "첫 행동이 무엇을 위한 것이면 좋을지 3가지 보기로 묻는다. 보기는 대화 맥락을 "
                                    + "반영해 서로 결이 다르게 — 완벽함을 좇는 것과 핵심을 지키는 것이 구분되게.",
                            "그렇다면 첫 행동은 무엇을 위한 연습이면 좋을까요?",
                            List.of(OptionItem.of("빠짐없이 완벽하게 해내기"),
                                    OptionItem.of("긴장해도 핵심을 먼저 지키기"),
                                    OptionItem.of("다른 사람보다 더 잘하기"))),
                    ScriptStep.action("action",
                            "그 목적에 맞춰 실천해 볼 행동 2가지를 제안한다. 각 보기는 제목과 한 줄 설명으로, "
                                    + "오늘 대화에 나온 구체적 내용을 반영한다.",
                            "그 목적에 맞춰 한 번 실천해 볼 행동을 골라볼까요?",
                            List.of(new OptionItem("핵심 세 줄 만들기",
                                            "오늘 문제가 된 일 하나를 골라 핵심을 각각 한 줄로 정리하기"),
                                    new OptionItem("1분 연습하기",
                                            "정리한 핵심을 1분 동안 소리 내어 말해보고 핵심이 들어갔는지 확인하기"))),
                    ScriptStep.measure("after_intensity",
                            "오늘의 대화를 통해, {일정}에서 느낀 {감정1과} 오늘의 감정 {감정2가} "
                                    + "지금은 어느 정도로 느껴지는지 표시해 주세요."
                                    + SCALE_HINT + BASELINE_NOTE,
                            MeasureField.SCHEDULE_EMOTION, MeasureField.CURRENT_EMOTION)),

            // 5. 짧은 기록형 — 강도 측정 + 한 문장.
            RetroMode.SHORT_RECORD, List.of(
                    ScriptStep.measure("intensity",
                            "오늘 {일정}에서 느낀 {감정1과} 지금 느끼는 {감정2가} 각각 어느 정도인지 표시해 "
                                    + "주세요." + SCALE_HINT,
                            MeasureField.SCHEDULE_EMOTION, MeasureField.CURRENT_EMOTION),
                    ScriptStep.text("one_line",
                            "오늘 있었던 일과 두 감정을 한 문장으로 남기게 한다. 잘 쓰려고 하지 않아도 "
                                    + "괜찮다고 덧붙인다.",
                            "오늘 있었던 일과 두 감정을 한 문장으로 남긴다면 어떻게 적고 싶나요? 잘 쓰려고 "
                                    + "하지 않아도 괜찮아요.")));

    public static List<ScriptStep> stepsOf(RetroMode mode) {
        return STEPS.get(mode);
    }

    // ── 슬라이더 라벨 ────────────────────────────────────────────────────

    /** 슬라이더 하나의 라벨. belief 는 자동적 사고가 있으면 따옴표로 인용한다. */
    public static String measureLabel(MeasureField field, String schedule,
            Emotion scheduleEmotion, Emotion currentEmotion, String thought, boolean beliefAfter) {
        return switch (field) {
            case SCHEDULE_EMOTION -> fill("{일정}에서 느낀 {감정1}", schedule, scheduleEmotion,
                    currentEmotion, null);
            case CURRENT_EMOTION -> fill("지금 느끼는 {감정2}", schedule, scheduleEmotion,
                    currentEmotion, null);
            case BELIEF -> beliefAfter || thought == null || thought.isBlank()
                    ? "처음 생각에 대한 믿음"
                    : "“" + thought + "”라는 생각에 대한 믿음";
        };
    }

    // ── 템플릿 채우기 ────────────────────────────────────────────────────

    private static final Pattern TOKEN =
            Pattern.compile("\\{(일정|감정1|감정2|생각)(으로|을|를|이|가|은|는|과|와|로)?\\}");

    /**
     * 토큰을 채우고, 토큰에 붙은 조사는 값의 받침에 맞게 교정한다.
     * 예: {@code {일정을}} + "산책" → "산책을", + "발표" → "발표를".
     */
    public static String fill(String template, String schedule, Emotion scheduleEmotion,
            Emotion currentEmotion, String thought) {
        Matcher m = TOKEN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = switch (m.group(1)) {
                case "일정" -> schedule == null ? "오늘 하루" : schedule;
                case "감정1" -> scheduleEmotion == null ? "그 감정" : scheduleEmotion.stem();
                case "감정2" -> currentEmotion == null ? "지금 감정" : currentEmotion.stem();
                default -> thought == null ? "그 생각" : thought;
            };
            String josa = m.group(2);
            String rep = josa == null ? value : value + correctJosa(value, josa);
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String correctJosa(String value, String josa) {
        return switch (josa) {
            case "을", "를" -> Josa.pick(value, "을", "를");
            case "이", "가" -> Josa.pick(value, "이", "가");
            case "은", "는" -> Josa.pick(value, "은", "는");
            case "과", "와" -> Josa.pick(value, "과", "와");
            case "으로", "로" -> ro(value);
            default -> josa;
        };
    }

    /** (으)로 — 받침이 없거나 ㄹ 받침이면 '로'. */
    private static String ro(String value) {
        if (value == null || value.isEmpty()) {
            return "로";
        }
        char last = value.charAt(value.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) {
            return "로";
        }
        int jong = (last - 0xAC00) % 28;
        return (jong == 0 || jong == 8) ? "로" : "으로";
    }
}
