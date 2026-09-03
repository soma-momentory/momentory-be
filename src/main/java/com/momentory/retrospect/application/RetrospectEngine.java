package com.momentory.retrospect.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.momentory.retrospect.application.event.CrisisDetected;
import com.momentory.retrospect.application.metering.LlmUsageLogger;
import com.momentory.retrospect.domain.AnswerGate;
import com.momentory.retrospect.domain.Choice;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.ExtractedEmotion;
import com.momentory.retrospect.domain.Message;
import com.momentory.retrospect.domain.Need;
import com.momentory.retrospect.domain.Needs;
import com.momentory.retrospect.domain.Phase;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.ScheduleItem;
import com.momentory.retrospect.domain.WishSentiment;
import com.momentory.retrospect.domain.assistant.DiaryChatAssistant;
import com.momentory.retrospect.domain.assistant.DiaryOutput;
import com.momentory.retrospect.domain.assistant.DiaryTurn;
import com.momentory.retrospect.domain.assistant.DiaryWriter;
import com.momentory.retrospect.domain.assistant.EmotionExtraction;
import com.momentory.retrospect.domain.assistant.EmotionExtractor;
import com.momentory.retrospect.domain.assistant.ExplorationAssistant;
import com.momentory.retrospect.domain.safety.AbuseGate;
import com.momentory.retrospect.domain.safety.Guidance;
import com.momentory.retrospect.domain.safety.PromptGuard;
import com.momentory.retrospect.domain.safety.SafetyLevel;
import com.momentory.retrospect.domain.safety.SafetyPolicy;
import com.momentory.retrospect.infrastructure.ai.LlmRole;

/**
 * 회고 대화 엔진 — 슬롯 채우기 상태 머신 (채팅흐름_v2).
 *
 * <pre>
 * diary_chat(≤6턴: 사건·감정·의미 슬롯) → await_branch(감정 더 알아볼까)
 *        └(선택 시)→ emotion_exploration(고정 3턴: 감정→바람→작은 행동) → complete
 *                                                          ↘ safety_hold ⇄ 이어가기 · ended(어뷰징)
 * </pre>
 *
 * <p><b>무상태 싱글턴이다.</b> 모든 세션 상태는 인자로 받은 {@link RetrospectState} 안에 있다.
 * 서버가 6턴·슬롯·종료를 통제하고, LLM({@link DiaryChatAssistant}·{@link EmotionExtractor}·
 * {@link ExplorationAssistant}·{@link DiaryWriter})은 질문·추출·후보 생성만 맡는다. 실패하면 폴백.
 */
@Component
public class RetrospectEngine {

    static final String BRANCH_EXPLORE = "감정을 더 알아볼래요";
    static final String BRANCH_VIEW = "일기 확인하러 갈래요";
    static final String WRITE_OWN = "직접 적기";
    static final String NOT_SURE = "아직 잘 모르겠어요";
    static final String HERE_END = "오늘은 여기까지 할래요";
    /** 단답 체크인 선지 — 이대로 일기를 보러 갈지 / 조금 더 이야기할지. */
    static final String GO_DIARY = "일기 보러 갈래요";
    static final String KEEP_TALKING = "조금 더 이야기할래요";

    /**
     * 이 턴 수를 넘긴 뒤 단답이 나오면 강제로 끌지 않고 "이대로 끝낼지/더 할지"를 물어 분기한다.
     * 그 전(초반)에는 그대로 되묻어 대화를 이어간다(끌고 간다).
     */
    private static final int DIARY_CHECKIN_AFTER_TURN = 3;

    /** 직접 입력·작은 행동의 방어 상한(글자) — 한 줄로 받는다. */
    private static final int CUSTOM_MAX_LENGTH = 200;

    private final SafetyPolicy safetyPolicy;
    private final DiaryChatAssistant diaryChatAssistant;
    private final EmotionExtractor emotionExtractor;
    private final ExplorationAssistant explorationAssistant;
    private final DiaryWriter diaryWriter;
    private final LlmUsageLogger usage;
    private final ApplicationEventPublisher events;
    private final int reaskCap;
    private final int abuseCap;

    public RetrospectEngine(SafetyPolicy safetyPolicy, DiaryChatAssistant diaryChatAssistant,
            EmotionExtractor emotionExtractor, ExplorationAssistant explorationAssistant,
            DiaryWriter diaryWriter, LlmUsageLogger usage, ApplicationEventPublisher events,
            @Value("${momentory.gate.reask-cap:1}") int reaskCap,
            @Value("${momentory.gate.abuse-cap:3}") int abuseCap) {
        this.safetyPolicy = safetyPolicy;
        this.diaryChatAssistant = diaryChatAssistant;
        this.emotionExtractor = emotionExtractor;
        this.explorationAssistant = explorationAssistant;
        this.diaryWriter = diaryWriter;
        this.usage = usage;
        this.events = events;
        this.reaskCap = reaskCap;
        this.abuseCap = abuseCap;
    }

    // ── 시작 ─────────────────────────────────────────────────────────────

    public ReplyDto start(RetrospectState state, StartCommand command) {
        return start(state, command, List.of());
    }

    public ReplyDto start(RetrospectState state, StartCommand command, List<String> restMethods) {
        state.restMethods(restMethods);
        ScheduleItem topic = pickTopic(command.schedules(), command.interest());
        if (topic != null) {
            state.begin(topic.name(), topic.emotion(), topic.id(), command.nickname(),
                    command.interest());
        } else {
            state.begin(null, null, null, command.nickname(), command.interest());
        }
        String text = openingQuestion(state);
        state.addAssistantMessage(text);
        usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(), Phase.DIARY_CHAT.key());
        return ReplyDto.question(text, Phase.DIARY_CHAT, state.safety().level());
    }

    /**
     * 대화로 이어갈 개인화 소재 하나 — 우선순위는 <b>관심분야 → 끝난 일 → 첫 번째</b>다.
     *
     * <p>관심분야가 이름에 담긴 일정을 먼저 본다(온보딩에서 고른 축이라 할 말이 많다). 없으면 오늘
     * <b>마친</b> 일정을 고른다 — 아직 안 한 일은 돌아볼 거리가 없어 첫 질문이 헛돈다. 둘 다 없으면
     * 목록의 첫 번째다.
     */
    private static ScheduleItem pickTopic(List<ScheduleItem> schedules, String interest) {
        if (schedules.isEmpty()) {
            return null;
        }
        if (interest != null && !interest.isBlank()) {
            String key = interest.strip();
            for (ScheduleItem s : schedules) {
                if (s.name() != null && s.name().contains(key)) {
                    return s;
                }
            }
        }
        for (ScheduleItem s : schedules) {
            if (s.completed()) {
                return s;
            }
        }
        return schedules.get(0);
    }

    private static String openingQuestion(RetrospectState state) {
        String who = blankName(state) ? "" : state.nickname().strip() + "님, ";
        if (state.hasSchedule()) {
            // 예/아니오("진행됐어요?")는 "아니"라는 비답변을 부르고 분기가 없어 막힌다 — 열린 질문으로.
            // 그 일정이 실제로 없었으면 사용자가 풀어 말하고, AI 가 다른 소재로 자연스럽게 옮긴다.
            return who + "오늘 " + state.schedule() + ", 어땠어요?";
        }
        return who + "오늘 하루 중에서 가장 기억에 남는 일은 뭐였어요?";
    }

    private static boolean blankName(RetrospectState state) {
        return state.nickname() == null || state.nickname().isBlank();
    }

    // ── 턴 처리 진입점 ───────────────────────────────────────────────────

    public ReplyDto handle(RetrospectState state, TurnCommand command) {
        Phase phase = state.phase();
        if (phase.isTerminal()) {
            return ReplyDto.alreadyFinished(phase);
        }
        return switch (phase) {
            case DIARY_CHAT -> handleDiaryTurn(state, command);
            case AWAIT_BRANCH -> handleBranch(state, command);
            case EMOTION_EXPLORATION -> handleExploration(state, command);
            case SAFETY_HOLD -> handleSafetyResume(state, command);
            default -> ReplyDto.alreadyFinished(phase);
        };
    }

    // ── 일기 작성 채팅 ───────────────────────────────────────────────────

    private ReplyDto handleDiaryTurn(RetrospectState state, TurnCommand command) {
        // 단답 체크인을 띄운 상태면, 이 턴은 그 선택('일기 보러 갈래요' vs '조금 더')에 대한 답이다.
        if (!state.lastOptions().isEmpty()) {
            return resolveDiaryCheckin(state, command);
        }
        if (!command.hasContent()) {
            return ReplyDto.question("편하게, 떠오르는 대로 적어주셔도 괜찮아요.", Phase.DIARY_CHAT,
                    state.safety().level());
        }
        Message userMessage = state.addUserMessage(command.content());

        SafetyPolicy.ScanResult scan = safetyPolicy.scan(command.content());
        mergeSafety(state, scan.level(), scan.flags(), userMessage.id());
        if (state.safety().level() == SafetyLevel.IMMINENT) {
            return safetyReply(state);
        }
        Optional<PromptGuard.Category> attack = PromptGuard.inspect(command.content());
        if (attack.isPresent()) {
            return deflectReply(state, Phase.DIARY_CHAT, attack.get());
        }
        if (!scan.level().atLeast(SafetyLevel.RISK)) {
            Optional<AbuseGate.Category> abuse = AbuseGate.inspect(command.content());
            if (abuse.isPresent()) {
                return abuseReply(state, Phase.DIARY_CHAT, abuse.get());
            }
        }
        state.resetAbuse();

        // 사용자가 그만하려 함 → 확인된 것만으로 일기 정리(즉시 종료).
        if (isStopRequest(command.content())) {
            state.markDiaryUserEnded();
            return finishDiaryChat(state, null);
        }

        // 단답 여부는 규칙 게이트로 감지만 한다 — 고정 문구로 되묻지 않는다(같은 문구 반복이 어색했다).
        // AI 가 만든 다음 질문으로 소재를 자연스럽게 넓히며 이어간다.
        boolean terse = AnswerGate.inspect(command.content()).isPresent();

        String question = null;
        String empathy = null;
        boolean noMoreToAsk = false;
        Optional<DiaryTurn> ai = diaryChatAssistant.turn(state, command.content());
        if (ai.isPresent()) {
            DiaryTurn t = ai.get();
            mergeSafety(state, t.safetyLevelOrNone(), t.safetyFlags(), userMessage.id());
            if (state.safety().level().stopsRetrospect()) {
                return safetyReply(state);
            }
            if (t.offTopic() || t.vague()) {
                terse = true;
            }
            state.event(t.event());
            state.addSecondaryEvents(t.secondaryEvents());
            state.meaning(t.meaning());
            if (t.emotionPresent()) {
                state.markEmotionSeen();
            }
            question = t.question();
            empathy = t.empathy();
            noMoreToAsk = t.noMoreToAsk();
        } else {
            usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(),
                    Phase.DIARY_CHAT.key());
        }
        // 짧게 답해도 한 턴으로 센다 — 그래야 3턴 이후 체크인·6턴 종료에 제대로 도달한다.
        state.bumpDiaryTurn();

        // 3턴을 넘겨서도 단답이 이어지면 강제로 끌지 않고 "이대로 끝낼지/더 할지"를 물어 분기한다.
        if (terse && !state.diaryTurnsExhausted()
                && state.diaryTurn() >= DIARY_CHECKIN_AFTER_TURN) {
            return presentDiaryCheckin(state);
        }
        // 다루는 사건(≤2)에서 더 물어볼 게 없다고 모델이 알리면 마무리한다 — 다른 소재로 넓히지
        // 않는다. 최소 턴은 지킨다(1~2턴 만에 끝나면 일기로 쓸 재료가 모자란다).
        if (state.diaryTurnsExhausted()
                || (noMoreToAsk && state.diaryTurn() >= RetrospectState.DIARY_MIN_TURNS)) {
            return finishDiaryChat(state, empathy);
        }
        String text = (question != null && !question.isBlank())
                ? breakAfterEmpathy(question.strip())
                : fallbackDiaryQuestion(state);
        state.addAssistantMessage(text);
        return ReplyDto.question(text, Phase.DIARY_CHAT, state.safety().level());
    }

    /**
     * 다음 빈 슬롯을 겨눈 폴백 질문 — AI 실패 시와 체크인 재개("조금 더")에서 쓴다.
     *
     * <p>⚠ <b>이 자리는 AI 를 거치지 않는다</b> — 프롬프트의 "소재를 넓히지 마세요" 규칙이 닿지
     * 않는다. 슬롯이 다 찼을 때 "조금 더 이야기해 주고 싶은 게 있을까요?" 처럼 대상을 열어 두면
     * 사용자가 새 소재를 꺼내고, 사건이 상한을 넘어 추출에서 버려진다(실기기에서 관측). 그래서
     * 사건이 이미 상한만큼 나왔으면 <b>지금 다루는 것 안</b>으로 질문을 좁힌다.
     */
    private static String fallbackDiaryQuestion(RetrospectState state) {
        if (state.event() == null) {
            return "그때 무슨 일이 있었는지 조금만 더 들려줄래요?";
        }
        if (!state.emotionSeen()) {
            return "그 순간에는 어떤 기분이 들었어요?";
        }
        if (state.meaning() == null) {
            return "지금 돌아보면 어떤 점이 가장 마음에 남아요?";
        }
        return state.knownEventCount() >= RetrospectState.MAX_EVENTS
                ? "지금까지 이야기한 것 중에, 조금 더 들려주고 싶은 게 있을까요?"
                : "조금 더 이야기해 주고 싶은 게 있을까요?";
    }

    /**
     * 일기 작성 종료 → 감정 추출(대화 전체) + 일기 생성 후 분기점 제시. 넘어가기 전에 마지막 답변에
     * 대한 AI 공감 한 문장이 있으면 전환 멘트 앞에 한 줄 붙인다(공감이 없으면 전환 멘트만).
     */
    private ReplyDto finishDiaryChat(RetrospectState state, String empathy) {
        generateDiary(state);
        state.changePhase(Phase.AWAIT_BRANCH);
        List<Choice> options = List.of(Choice.of(BRANCH_EXPLORE), Choice.of(BRANCH_VIEW));
        state.lastOptions(options);
        String transition = "오늘 이야기는 일기로 정리해뒀어요. 지금 느낀 감정을 조금 더 알아볼까요?";
        String lead = (empathy != null && !empathy.isBlank()) ? empathy.strip() + "\n" : "";
        String text = lead + transition;
        state.addAssistantMessage(text + "\n" + optionLines(options));
        usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(), Phase.AWAIT_BRANCH.key());
        return ReplyDto.choices(text, Phase.AWAIT_BRANCH, toOptionDtos(options),
                state.safety().level());
    }

    /** 대화 전체에서 감정을 뽑고 일기 초안을 만들어 state 에 담는다 — 종료 직전 한 번. */
    private void generateDiary(RetrospectState state) {
        EmotionExtraction extraction = emotionExtractor.extract(state);
        state.events(extraction.events());
        state.emotions(extraction.emotions());
        state.inferredEmotion(extraction.inferredEmotion());
        DiaryOutput out = diaryWriter.write(state).orElseGet(() -> fallbackDiary(state));
        state.diaryDraft(out.diary());
    }

    // ── 단답 체크인 (3턴 이후) ───────────────────────────────────────────

    /**
     * 3턴을 넘긴 뒤 단답이 나오면 강제로 끌지 않고 공감 + 「일기 보러 갈래요 / 조금 더 이야기할래요」를
     * 물어 분기한다. 이 물음은 일기 턴을 소모하지 않는다({@code diaryTurn} 증가 없음).
     */
    private ReplyDto presentDiaryCheckin(RetrospectState state) {
        List<Choice> options = List.of(Choice.of(GO_DIARY), Choice.of(KEEP_TALKING));
        state.lastOptions(options);
        String text = "지금까지 이야기해줘서 고마워요.\n이대로 일기를 보러 갈까요, 아니면 조금 더 이야기해볼까요?";
        state.addAssistantMessage(text + "\n" + optionLines(options));
        usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(), Phase.DIARY_CHAT.key());
        return ReplyDto.choices(text, Phase.DIARY_CHAT, toOptionDtos(options), state.safety().level());
    }

    /**
     * 단답 체크인에 대한 응답 처리. 「일기 보러 갈래요」면 지금까지의 이야기로 일기를 정리해 곧장 마무리하고,
     * 그 외(「조금 더」·자유 입력)면 체크인을 접고 대화를 이어간다. 자유 입력이면 그 내용을 이번 답으로 삼는다.
     */
    private ReplyDto resolveDiaryCheckin(RetrospectState state, TurnCommand command) {
        if (picked(state, command, GO_DIARY)) {
            state.addUserMessage(GO_DIARY);
            state.lastOptions(List.of());
            state.markDiaryUserEnded();
            generateDiary(state);
            return finishComplete(state); // 감정 탐색 없이 일기로 바로 — 사용자가 '일기 보러 가기'를 골랐다
        }
        state.lastOptions(List.of());
        // 자유 입력이면 그 답으로 대화를 이어간다(정상 일기 턴 처리로 넘긴다).
        if (command.hasContent() && !picked(state, command, KEEP_TALKING)) {
            return handleDiaryTurn(state, command);
        }
        state.addUserMessage(KEEP_TALKING);
        String text = "좋아요, 편하게 이어가 봐요. " + fallbackDiaryQuestion(state);
        state.addAssistantMessage(text);
        return ReplyDto.question(text, Phase.DIARY_CHAT, state.safety().level());
    }

    /** AI 없이 슬롯만으로 만든 최소한의 일기 — 일기 없이 끝나는 것보다는 낫다. */
    private static DiaryOutput fallbackDiary(RetrospectState state) {
        StringBuilder sb = new StringBuilder();
        if (state.event() != null) {
            sb.append(state.event());
        }
        if (state.meaning() != null) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(state.meaning());
        }
        if (sb.length() == 0) {
            sb.append(state.hasSchedule() ? "오늘 " + state.schedule() + "이 있었다."
                    : "오늘 하루를 돌아봤다.");
        }
        return new DiaryOutput(sb.toString().strip(), null);
    }

    // ── 분기점 ───────────────────────────────────────────────────────────

    private ReplyDto handleBranch(RetrospectState state, TurnCommand command) {
        Optional<Choice> chosen = resolveSingle(state, command);
        if (chosen.isPresent() && BRANCH_EXPLORE.equals(chosen.get().label())) {
            state.addUserMessage(BRANCH_EXPLORE);
            state.enterExploration();
            return presentEmotionConfirm(state);
        }
        // 그 외(일기 확인·미선택) → 감정 탐색 미진행이 기본값. 바람 카드 없이 마무리.
        state.addUserMessage(chosen.map(Choice::label).orElse(BRANCH_VIEW));
        return finishComplete(state);
    }

    // ── 감정 탐색 (고정 3턴) ─────────────────────────────────────────────

    private ReplyDto handleExploration(RetrospectState state, TurnCommand command) {
        return switch (state.explorationTurn()) {
            case 0 -> handleEmotionConfirm(state, command);
            case 1 -> handleNeedConfirm(state, command);
            default -> handleSmallAction(state, command);
        };
    }

    /**
     * 1턴 — 감정 확인 질문 + 후보 제시.
     *
     * <p>후보는 추출한 감정이다. 대화에 감정 표현이 아예 없어 비면 모델이 고른 추론 감정
     * ({@link RetrospectState#inferredEmotion()})을 대신 보여준다 — 빈 선택지를 내밀지 않기 위해서다.
     * 추론값은 사용자가 고르기 전까지 어디에도 기록되지 않는다(고르면 확인 감정이 된다).
     */
    private ReplyDto presentEmotionConfirm(RetrospectState state) {
        List<Choice> options = new ArrayList<>();
        for (Emotion e : distinctNormalized(state.emotions())) {
            options.add(Choice.of(e.label()));
        }
        if (options.isEmpty() && state.inferredEmotion() != null) {
            options.add(Choice.of(state.inferredEmotion().label()));
        }
        state.lastOptions(options);
        String text = "그 일을 떠올렸을 때, 지금 가장 가까운 감정은 무엇인가요?";
        state.addAssistantMessage(text + "\n" + optionLines(options));
        return ReplyDto.choices(text, Phase.EMOTION_EXPLORATION, toOptionDtos(options),
                state.safety().level());
    }

    private ReplyDto handleEmotionConfirm(RetrospectState state, TurnCommand command) {
        if (picked(state, command, HERE_END)) {
            return finishComplete(state);
        }
        List<Emotion> chosen = new ArrayList<>();
        for (Choice c : resolveMany(state, command)) {
            if (!c.input() && !NOT_SURE.equals(c.label())) {
                Emotion.fromLabel(c.label()).ifPresent(chosen::add);
            }
        }
        // 자유 입력 텍스트도 감정으로 정규화 시도(실패해도 진행).
        if (command.hasContent()) {
            Emotion.fromLabel(command.content().strip()).ifPresent(chosen::add);
        }
        state.confirmEmotions(limit1(chosen));
        state.addUserMessage(chosen.isEmpty()
                ? (command.hasContent() ? command.content().strip() : NOT_SURE)
                : labelsOf(chosen));
        state.bumpExplorationTurn();
        return presentNeedConfirm(state);
    }

    /** 2턴 — 바람 확인 질문(감정 성격별 워딩) + 고정 욕구 후보. */
    private ReplyDto presentNeedConfirm(RetrospectState state) {
        List<Need> suggested = explorationAssistant.suggestNeeds(state);
        if (suggested.isEmpty()) {
            suggested = Needs.ALL.subList(0, 3);
        }
        List<Choice> options = new ArrayList<>();
        for (Need n : suggested.stream().limit(3).toList()) {
            options.add(Choice.of(n.word(), n.meaning()));
        }
        state.lastOptions(options);
        String text = WishSentiment.of(state.confirmedEmotions()).isPositive()
                ? "그때 오늘 내 마음을 채워준 것은 무엇에 가까웠을까요?"
                : "내 마음이 진정으로 바랐던 것은 무엇에 가까웠을까요?";
        state.addAssistantMessage(text + "\n" + optionLines(options));
        return ReplyDto.choices(text, Phase.EMOTION_EXPLORATION, toOptionDtos(options),
                state.safety().level());
    }

    private ReplyDto handleNeedConfirm(RetrospectState state, TurnCommand command) {
        if (picked(state, command, HERE_END)) {
            return finishComplete(state);
        }
        List<Need> chosen = new ArrayList<>();
        for (Choice c : resolveMany(state, command)) {
            if (!c.input() && !NOT_SURE.equals(c.label())) {
                Needs.byWord(c.label()).ifPresent(chosen::add);
            }
        }
        state.chooseNeeds(limit1(chosen));
        // 자유 입력 텍스트는 '바랐던 모습' 폴백으로 담는다(AI 생성이 우선).
        if (command.hasContent()) {
            state.desiredState(clamp(command.content()));
        }
        state.addUserMessage(chosen.isEmpty()
                ? (command.hasContent() ? clamp(command.content()) : NOT_SURE)
                : needWordsOf(chosen));
        state.bumpExplorationTurn();
        return presentSmallAction(state);
    }

    /** 3턴 — 작은 행동 질문 + 후보. */
    private ReplyDto presentSmallAction(RetrospectState state) {
        List<String> actions = explorationAssistant.suggestActions(state);
        if (actions.isEmpty()) {
            actions = fallbackActions(state);
        }
        List<Choice> options = new ArrayList<>();
        for (String a : actions.stream().limit(3).toList()) {
            options.add(Choice.of(a));
        }
        state.lastOptions(options);
        String text = "이 마음을 위해 오늘이나 다음에 해볼 수 있는 작은 행동이 있을까요?";
        state.addAssistantMessage(text + "\n" + optionLines(options));
        return ReplyDto.choices(text, Phase.EMOTION_EXPLORATION, toOptionDtos(options),
                state.safety().level());
    }

    private ReplyDto handleSmallAction(RetrospectState state, TurnCommand command) {
        if (picked(state, command, HERE_END)) {
            return finishComplete(state); // 행동 미정 허용 — 실패로 처리하지 않는다.
        }
        if (command.hasContent()) {
            state.smallAction(clamp(command.content()));
            state.addUserMessage(clamp(command.content()));
        } else {
            Optional<Choice> c = resolveSingle(state, command);
            if (c.isPresent() && !c.get().input()) {
                state.smallAction(c.get().label());
                state.addUserMessage(c.get().label());
            }
        }
        state.bumpExplorationTurn();
        return finishComplete(state);
    }

    /** 쉬는 방법 선호가 있으면 반영한 폴백 행동. */
    private static List<String> fallbackActions(RetrospectState state) {
        if (!state.restMethods().isEmpty()) {
            return List.of(state.restMethods().get(0) + " 10분 해보기", "오늘 느낀 점 한 줄 메모하기");
        }
        return List.of("오늘 느낀 점을 한 문장으로 적어보기", "잠들기 전 10분 알림 끄고 쉬기");
    }

    // ── 종료 ─────────────────────────────────────────────────────────────

    /**
     * 최종 종료 — 일기(항상)와 바람 카드(감정 탐색을 거친 경우에만)를 응답에 싣는다.
     *
     * <p>바람 카드는 현재 기존 행동 카드 계약(상황·행동)에 매핑해 저장한다(plan A). 풍부한 필드
     * (감정·바람·바랐던 모습·성격)와 다중 감정 태그·마이그레이션은 후속 증분에서 얹는다.
     */
    private ReplyDto finishComplete(RetrospectState state) {
        state.changePhase(Phase.COMPLETE);
        ReplyDto.DiaryDto diary = new ReplyDto.DiaryDto(null, state.diaryDraft(),
                diaryEmotionKeys(state));
        // 바람 카드는 감정 탐색을 거친 경우에만 — 작은 행동을 안 정했어도 만든다(빈칸 허용).
        ReplyDto.WishCardDto card = state.explorationEntered() ? buildWishCard(state) : null;
        String text = "오늘 이야기를 정리해뒀어요. 일기에서 천천히 확인해봐요.";
        state.addAssistantMessage(text);
        return ReplyDto.completed(text, diary, card, state.safety().level());
    }

    /** 상태의 감정 탐색 슬롯으로 바람 카드를 만든다 — 상황은 핵심 event 요약. */
    private static ReplyDto.WishCardDto buildWishCard(RetrospectState state) {
        String situation = state.event() != null ? state.event()
                : state.hasSchedule() ? state.schedule() + "에서 있었던 일" : "오늘 하루 있었던 일";
        List<String> emotions = state.confirmedEmotions().stream().map(Emotion::key).toList();
        List<ReplyDto.NeedDto> needs = state.needs().stream()
                .map(n -> new ReplyDto.NeedDto(n.word(), n.meaning())).toList();
        String sentiment = WishSentiment.of(state.confirmedEmotions()).key();
        // 바랐던/좋았던 모습 = 확인한 바람(욕구)의 뜻. 욕구가 없으면 사용자 입력·빈칸으로 폴백.
        String desiredState = state.needs().isEmpty() ? state.desiredState()
                : state.needs().stream().map(Need::meaning).collect(Collectors.joining(" "));
        return new ReplyDto.WishCardDto(null, situation, emotions, needs, desiredState,
                state.smallAction(), sentiment);
    }

    /**
     * 일기 감정 태그(키) — 확인 감정 ∪ 추출된 정규화 감정(중복 제거, 확인된 것 우선). 완료 응답에 실어
     * 화면이 저장 직후에도(서버 재조회 전) 일기 감정을 바로 보여준다(서버의 emotionTags 와 같은 규칙).
     */
    private static List<String> diaryEmotionKeys(RetrospectState state) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Emotion e : state.confirmedEmotions()) {
            keys.add(e.key());
        }
        for (ExtractedEmotion e : state.emotions()) {
            if (e.normalized() != null) {
                keys.add(e.normalized().key());
            }
        }
        return List.copyOf(keys);
    }

    // ── 되돌리기(게이트·안전·어뷰징) ────────────────────────────────────

    private static AnswerGate.HoldReason holdReasonFor(boolean offTopic) {
        return offTopic ? AnswerGate.HoldReason.NON_ANSWER : AnswerGate.HoldReason.SOFT_EVASION;
    }

    private ReplyDto holdReply(RetrospectState state, Phase phase, AnswerGate.HoldReason reason) {
        String text = AnswerGate.message(reason);
        state.addAssistantMessage(text);
        usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(), phase.key());
        return ReplyDto.question(text, phase, state.safety().level());
    }

    private ReplyDto deflectReply(RetrospectState state, Phase phase, PromptGuard.Category category) {
        String text = PromptGuard.message(category);
        state.addAssistantMessage(text);
        usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(), phase.key());
        return ReplyDto.question(text, phase, state.safety().level());
    }

    private ReplyDto abuseReply(RetrospectState state, Phase phase, AbuseGate.Category category) {
        state.bumpAbuse();
        if (abuseCap > 0 && state.abuseStreak() >= abuseCap) {
            String text = AbuseGate.endMessage(category);
            state.addAssistantMessage(text);
            state.changePhase(Phase.ENDED);
            usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(), Phase.ENDED.key());
            return ReplyDto.ended(text, state.safety().level());
        }
        String text = AbuseGate.message(category);
        state.addAssistantMessage(text);
        usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(), phase.key());
        return ReplyDto.question(text, phase, state.safety().level());
    }

    private void mergeSafety(RetrospectState state, SafetyLevel level, List<String> flags,
            String messageId) {
        boolean escalated = state.mergeSafety(level, flags, messageId);
        if (escalated) {
            events.publishEvent(new CrisisDetected(state.id(), state.safety().level(),
                    state.safety().flags(), messageId));
        }
    }

    private ReplyDto safetyReply(RetrospectState state) {
        SafetyLevel level = state.safety().level();
        Guidance guidance = safetyPolicy.guidanceFor(level);
        String text = guidance != null ? guidance.render() : "잠시 멈추고 안전을 먼저 챙겨요.";
        state.addAssistantMessage(text);
        state.holdForSafety();
        return ReplyDto.safetyHold(text, level);
    }

    private ReplyDto handleSafetyResume(RetrospectState state, TurnCommand command) {
        Phase resumed = state.resumeFromHold();
        return switch (resumed) {
            case AWAIT_BRANCH -> handleBranch(state, command);
            case EMOTION_EXPLORATION -> handleExploration(state, command);
            default -> handleDiaryTurn(state, command);
        };
    }

    // ── 선택지 해석·변환 도우미 ─────────────────────────────────────────

    private static Optional<Choice> resolveSingle(RetrospectState state, TurnCommand command) {
        return command.hasOptions() ? state.resolveChoice(command.firstOption()) : Optional.empty();
    }

    private static List<Choice> resolveMany(RetrospectState state, TurnCommand command) {
        List<Choice> out = new ArrayList<>();
        for (String id : command.optionIds()) {
            state.resolveChoice(id).ifPresent(out::add);
        }
        return out;
    }

    private static boolean picked(RetrospectState state, TurnCommand command, String label) {
        return resolveMany(state, command).stream().anyMatch(c -> label.equals(c.label()));
    }

    private static List<Emotion> distinctNormalized(List<ExtractedEmotion> emotions) {
        Set<Emotion> seen = new LinkedHashSet<>();
        for (ExtractedEmotion e : emotions) {
            if (e.normalized() != null) {
                seen.add(e.normalized());
            }
        }
        return new ArrayList<>(seen).stream().limit(4).toList();
    }

    private static <T> List<T> limit2(List<T> items) {
        return items.size() <= 2 ? items : items.subList(0, 2);
    }

    /** 감정 탐색은 딱 하나만 고른다(단일 선택). */
    private static <T> List<T> limit1(List<T> items) {
        return items.isEmpty() ? items : items.subList(0, 1);
    }

    private static String labelsOf(List<Emotion> emotions) {
        return String.join(", ", emotions.stream().map(Emotion::label).toList());
    }

    private static String needWordsOf(List<Need> needs) {
        return String.join(", ", needs.stream().map(Need::word).toList());
    }

    private static String clamp(String s) {
        String t = s.strip();
        return t.length() > CUSTOM_MAX_LENGTH ? t.substring(0, CUSTOM_MAX_LENGTH) : t;
    }

    private static List<ReplyDto.OptionDto> toOptionDtos(List<Choice> options) {
        List<ReplyDto.OptionDto> out = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            Choice c = options.get(i);
            out.add(new ReplyDto.OptionDto(String.valueOf(i + 1), c.label(), c.description(), null,
                    c.input() ? Boolean.TRUE : null));
        }
        return out;
    }

    private static String optionLines(List<Choice> options) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < options.size(); i++) {
            Choice c = options.get(i);
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("  ").append(i + 1).append(". ").append(c.label());
            if (c.description() != null && !c.description().isBlank()) {
                sb.append(" — ").append(c.description());
            }
        }
        return sb.toString();
    }

    // ── 그만하기 감지 ────────────────────────────────────────────────────

    private static final List<String> STOP_PHRASES = List.of(
            "그만", "됐어", "됐어요", "그만할래", "그만할게", "이제 그만", "더 말할 건 없",
            "여기까지", "끝낼래", "마칠래", "그만하고 싶");

    private static boolean isStopRequest(String content) {
        if (content == null) {
            return false;
        }
        String t = content.strip();
        return t.length() <= 15 && STOP_PHRASES.stream().anyMatch(t::contains);
    }

    /** 공감 문장과 질문 사이 줄바꿈 — 한 덩어리로 붙어 읽기 힘들다는 피드백 반영. */
    static String breakAfterEmpathy(String text) {
        if (text == null || text.contains("\n")) {
            return text;
        }
        int question = text.indexOf('?');
        int limit = question >= 0 ? question : text.length();
        int boundary = text.lastIndexOf(". ", limit);
        if (boundary < 0) {
            return text;
        }
        return text.substring(0, boundary + 1) + "\n" + text.substring(boundary + 2);
    }
}
