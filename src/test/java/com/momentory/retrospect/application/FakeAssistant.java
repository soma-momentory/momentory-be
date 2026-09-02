package com.momentory.retrospect.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.momentory.retrospect.domain.ExtractedEmotion;
import com.momentory.retrospect.domain.ExtractedEvent;
import com.momentory.retrospect.domain.ExtractedKeyword;
import com.momentory.retrospect.domain.Need;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.DiaryChatAssistant;
import com.momentory.retrospect.domain.assistant.DiaryOutput;
import com.momentory.retrospect.domain.assistant.DiaryTurn;
import com.momentory.retrospect.domain.assistant.DiaryWriter;
import com.momentory.retrospect.domain.assistant.EmotionExtraction;
import com.momentory.retrospect.domain.assistant.EmotionExtractor;
import com.momentory.retrospect.domain.assistant.ExplorationAssistant;

/**
 * v2 AI 포트 4종의 페이크 — 네트워크 없이 엔진 흐름을 결정적으로 검증한다.
 *
 * <p>일기 작성 턴은 설정된 슬롯(사건·의미·감정 표현 여부)과 질문을 돌려주고, 감정 추출·욕구·행동
 * 후보도 설정값으로 준다. {@code fail*} 플래그를 켜면 empty/빈 목록을 돌려 폴백 경로를 검증한다.
 */
class FakeAssistant
        implements DiaryChatAssistant, EmotionExtractor, ExplorationAssistant, DiaryWriter {

    // 일기 작성 턴이 돌려줄 슬롯·질문
    boolean failDiaryChat;
    String turnEvent;
    String turnMeaning;
    boolean turnEmotionPresent;
    String turnQuestion = "조금 더 들려줄래요?";
    String turnEmpathy;
    boolean turnOffTopic;
    boolean turnVague;
    String turnSafetyLevel = "none";

    // 대화 끝 사건·감정 추출 결과
    final List<ExtractedEvent> events = new ArrayList<>();
    final List<ExtractedEmotion> emotions = new ArrayList<>();
    final List<ExtractedKeyword> keywords = new ArrayList<>();
    // 감정 탐색 후보
    final List<Need> needs = new ArrayList<>();
    final List<String> actions = new ArrayList<>();

    // 일기 생성
    boolean failDiary;

    // 호출 기록
    int diaryTurnCalls;
    int extractCalls;
    int diaryWriteCalls;
    final List<String> diaryTurnInputs = new ArrayList<>();

    @Override
    public Optional<DiaryTurn> turn(RetrospectState state, String userText) {
        diaryTurnCalls++;
        diaryTurnInputs.add(userText);
        if (failDiaryChat) {
            return Optional.empty();
        }
        return Optional.of(new DiaryTurn(turnEvent, List.of(), turnMeaning, turnEmotionPresent,
                turnQuestion, turnEmpathy, turnSafetyLevel, List.of(), turnOffTopic, turnVague));
    }

    @Override
    public EmotionExtraction extract(RetrospectState state) {
        extractCalls++;
        return new EmotionExtraction(List.copyOf(events), List.copyOf(emotions),
                List.copyOf(keywords));
    }

    @Override
    public List<Need> suggestNeeds(RetrospectState state) {
        return List.copyOf(needs);
    }

    @Override
    public List<String> suggestActions(RetrospectState state) {
        return List.copyOf(actions);
    }

    @Override
    public Optional<DiaryOutput> write(RetrospectState state) {
        diaryWriteCalls++;
        if (failDiary) {
            return Optional.empty();
        }
        return Optional.of(new DiaryOutput("오늘의 일기.", null));
    }
}
