package com.momentory.retrospect.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.momentory.retrospect.domain.assistant.DiaryOutput;
import com.momentory.retrospect.domain.assistant.DiaryWriter;
import com.momentory.retrospect.domain.assistant.TurnScript;
import com.momentory.retrospect.domain.assistant.TurnScripter;
import com.momentory.retrospect.domain.assistant.UnderstandingCheck;
import com.momentory.retrospect.domain.assistant.UnderstandingChecker;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.script.OptionItem;
import com.momentory.retrospect.domain.script.ScriptStep;

/**
 * AI 포트 3종의 페이크 — 네트워크 없이 엔진 흐름을 검증한다.
 *
 * <p>기본은 성공 경로: 이해 확인·턴 문구·일기를 예측 가능한 문구로 돌려준다.
 * {@code fail*} 플래그를 켜면 empty 를 돌려줘 폴백 경로를 검증한다.
 */
class FakeAssistant implements UnderstandingChecker, TurnScripter, DiaryWriter {

    boolean failUnderstanding;
    boolean failTurns;
    boolean failDiary;
    String turnSafetyLevel = "none";
    /** true 로 켜면 다음 G2/G1 판정이 이탈(offTopic)로 나와 엔진이 되묻는다. */
    boolean turnOffTopic;
    boolean understandingOffTopic;
    /** true 로 켜면 다음 G2/G1 판정이 얼버무림(vague)으로 나와 엔진이 발판 멘트로 되묻는다. */
    boolean turnVague;
    boolean understandingVague;
    /** null 이면 기본 문구("[AI] <stepId> 질문")를 쓴다. */
    String turnMessage;

    int understandingCalls;
    int diaryCalls;
    final List<String> scriptedStepIds = new ArrayList<>();

    @Override
    public Optional<UnderstandingCheck> check(RetrospectState state, String firstAnswer) {
        understandingCalls++;
        if (failUnderstanding) {
            return Optional.empty();
        }
        return Optional.of(new UnderstandingCheck(
                "모의 면접에서 답변을 제대로 하지 못해 불안했고, 다른 사람들과 비교하면서 지금은 "
                        + "우울해진 것 같네요.",
                "모의 면접에서 준비한 내용을 제대로 말하지 못함",
                "none", List.of(), understandingOffTopic, understandingVague, false));
    }

    @Override
    public Optional<TurnScript> script(RetrospectState state, ScriptStep step) {
        scriptedStepIds.add(step.id());
        if (failTurns) {
            return Optional.empty();
        }
        List<OptionItem> options = new ArrayList<>();
        if (step.isChoice()) {
            for (int i = 1; i <= step.optionCount(); i++) {
                options.add(new OptionItem("[" + step.id() + "] 보기" + i,
                        step.describedOptions() ? "보기" + i + " 설명" : null));
            }
        }
        String message = turnMessage != null ? turnMessage : "[AI] " + step.id() + " 질문";
        return Optional.of(new TurnScript(message, options, turnSafetyLevel, List.of(),
                turnOffTopic, turnVague, false));
    }

    @Override
    public Optional<DiaryOutput> write(RetrospectState state) {
        diaryCalls++;
        if (failDiary) {
            return Optional.empty();
        }
        return Optional.of(new DiaryOutput("오늘의 그냥 일기.", "오늘의 리프레이밍 일기."));
    }
}
