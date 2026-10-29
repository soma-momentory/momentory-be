package com.momentory.retrospect.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.momentory.retrospect.application.event.CrisisDetected;
import com.momentory.retrospect.application.event.TodayActionAnswered;
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
import com.momentory.retrospect.domain.TodayAction;
import com.momentory.retrospect.domain.TodayActionResult;
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
import com.momentory.retrospect.domain.script.Josa;
import com.momentory.retrospect.infrastructure.ai.LlmRole;

/**
 * 회고 대화 엔진 — 슬롯 채우기 상태 머신 (채팅흐름_v2).
 *
 * <pre>
 * diary_chat(≤5턴: 사건·감정·의미 슬롯) → (사건이 둘이면 await_branch: 사건 고르기)
 *        → emotion_exploration(2턴: 감정→바람) → complete   ※ 「그만할래요」는 바로 complete
 *   작은 행동은 회고에서 고르지 않고 다음 날 바바의 편지에서 고른다(편지 PRD 14-1).
 *                                                          ↘ safety_hold ⇄ 이어가기 · ended(어뷰징)
 * </pre>
 *
 * <p><b>무상태 싱글턴이다.</b> 모든 세션 상태는 인자로 받은 {@link RetrospectState} 안에 있다.
 * 서버가 5턴·슬롯·종료를 통제하고, LLM({@link DiaryChatAssistant}·{@link EmotionExtractor}·
 * {@link ExplorationAssistant}·{@link DiaryWriter})은 질문·추출·후보 생성만 맡는다. 실패하면 폴백.
 */
@Component
public class RetrospectEngine {

    /** 옛 분기점 선지 — 배포 전에 분기 답을 기다리던 세션만 받는다(새 세션은 분기 없이 감정 탐색으로 간다). */
    static final String BRANCH_EXPLORE = "감정을 더 알아볼래요";
    static final String BRANCH_VIEW = "일기 확인하러 갈래요";
    static final String WRITE_OWN = "직접 적기";
    static final String NOT_SURE = "아직 잘 모르겠어요";
    static final String HERE_END = "오늘은 여기까지 할래요";
    /** 단답 체크인 선지 — 이제 정리할지(감정 · 바람으로 넘어감) / 조금 더 이야기할지. */
    static final String WRAP_UP = "이제 정리할래요";
    /** 옛 단답 체크인 · 사건 고르기 선지 — 배포 전 세션만 받는다. */
    static final String GO_DIARY = "일기 보러 갈래요";
    static final String KEEP_TALKING = "조금 더 이야기할래요";

    /**
     * 이 턴 수를 넘긴 뒤 단답이 나오면 강제로 끌지 않고 "이대로 끝낼지/더 할지"를 물어 분기한다.
     * 그 전(초반)에는 그대로 되묻어 대화를 이어간다(끌고 간다).
     */
    private static final int DIARY_CHECKIN_AFTER_TURN = 3;

    /** 직접 입력의 방어 상한(글자) — 한 줄로 받는다. */
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
        return start(state, command, restMethods, null);
    }

    /**
     * 오늘의 작은 일({@code todayAction})이 있으면 원래 첫 질문 <b>앞에</b> 「해 봤어요?」를 한 번 묻고,
     * 답을 받은 뒤 원래 첫 질문을 이어서 묻는다(바바의 편지 PRD 4-5 · 14-4). 이 물음은 일기 턴을 쓰지 않는다.
     */
    public ReplyDto start(RetrospectState state, StartCommand command, List<String> restMethods,
            TodayAction todayAction) {
        state.restMethods(restMethods);
        ScheduleItem topic = pickTopic(command.schedules(), command.interest());
        if (topic != null) {
            state.begin(topic.name(), topic.emotion(), topic.id(), command.nickname(),
                    command.interest());
        } else {
            state.begin(null, null, null, command.nickname(), command.interest());
        }
        state.scheduleHint(topic != null && !directTopic(topic));
        String text = openingQuestion(state, command.schedules());
        usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(), Phase.DIARY_CHAT.key());
        if (todayAction != null) {
            return askTodayAction(state, todayAction, text);
        }
        state.addAssistantMessage(text);
        return ReplyDto.question(text, Phase.DIARY_CHAT, state.safety().level());
    }

    // ── 오늘의 작은 일 (바바의 편지) ─────────────────────────────────────

    static final String TRIED = "해 봤어요";
    static final String NOT_TRIED = "못 했어요";
    static final String WAS_GOOD = "좋았어요";
    static final String WAS_SO_SO = "그저 그랬어요";
    static final String OTHER_TOPIC = "다른 얘기 할래요";

    /** 맞장구는 고정 문구다 — AI 를 부르지 않는다. 뒤에 원래 첫 질문이 붙는다. */
    private static final Map<String, String> TODAY_ACTION_ACKS = Map.of(
            TRIED, "해 봤군요, 잘했어요.",
            NOT_TRIED, "못 해도 괜찮아요. 그런 날도 있죠.",
            WAS_GOOD, "좋았다니 다행이에요.",
            WAS_SO_SO, "그럴 때도 있죠.",
            OTHER_TOPIC, "좋아요, 오늘 이야기부터 해요.");

    private ReplyDto askTodayAction(RetrospectState state, TodayAction action, String followUp) {
        String who = blankName(state) ? "" : state.nickname().strip() + "님, ";
        String text;
        List<Choice> options;
        if (action.alreadyDone()) {
            text = "오늘 「" + action.title() + "」 해 봤네요. 해 보니 어땠어요?";
            options = List.of(Choice.of(WAS_GOOD), Choice.of(WAS_SO_SO), Choice.of(OTHER_TOPIC));
        } else {
            text = who + "오늘 「" + action.title() + "」 해 봤어요?";
            options = List.of(Choice.of(TRIED), Choice.of(NOT_TRIED), Choice.of(OTHER_TOPIC));
        }
        state.askTodayAction(action, followUp);
        state.lastOptions(options);
        state.addAssistantMessage(text + "\n" + optionLines(options));
        return ReplyDto.choices(text, Phase.DIARY_CHAT, toOptionDtos(options), state.safety().level());
    }

    /**
     * 「해 봤어요?」에 대한 답. 선택지를 고르면 결과를 기록(이벤트)하고 맞장구 + 원래 첫 질문을 잇는다.
     * 자유 입력이면 기록하지 않고 그 글로 일기 대화를 시작한다(단답 체크인의 자유 입력과 같다).
     */
    private ReplyDto resolveTodayAction(RetrospectState state, TurnCommand command) {
        TodayAction action = state.todayAction();
        String label = resolveMany(state, command).stream().map(Choice::label)
                .filter(TODAY_ACTION_ACKS::containsKey).findFirst().orElse(null);
        state.lastOptions(List.of());
        state.answerTodayAction();
        if (label == null) {
            return handleDiaryTurn(state, command);
        }
        state.addUserMessage(label);
        // 이미 쉼터에서 해 본 사람은 무엇을 고르든 해 본 것이다. 카드는 이미 체크돼 있다.
        TodayActionResult result = action.alreadyDone() ? TodayActionResult.DONE
                : TRIED.equals(label) ? TodayActionResult.DONE
                : NOT_TRIED.equals(label) ? TodayActionResult.NOT_DONE
                : TodayActionResult.SKIPPED;
        events.publishEvent(new TodayActionAnswered(action.letterActionId(), result));
        String text = TODAY_ACTION_ACKS.get(label) + " " + action.followUp();
        state.addAssistantMessage(text);
        usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(), Phase.DIARY_CHAT.key());
        return ReplyDto.question(text, Phase.DIARY_CHAT, state.safety().level());
    }

    private static final List<String> DIRECT_TOPIC_KEYWORDS =
            List.of("약속", "회의", "면접", "발표", "시험", "모임");

    /** 관심분야는 사건성이 있는 후보 사이에서만 우선순위를 정한다. */
    private static List<String> interestKeywords(String interest) {
        if (interest == null) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        if (interest.contains("학업")) keys.addAll(List.of("시험", "과제", "수업", "발표", "공부"));
        if (interest.contains("취업")) keys.addAll(List.of("면접", "지원서", "자소서", "채용", "인턴"));
        if (interest.contains("인간관계")) keys.addAll(List.of("약속", "모임", "친구", "데이트"));
        if (interest.contains("건강")) keys.addAll(List.of("운동", "헬스", "러닝", "병원", "검진"));
        return keys;
    }

    private static boolean directTopic(ScheduleItem item) {
        return item.name() != null && DIRECT_TOPIC_KEYWORDS.stream().anyMatch(item.name()::contains);
    }

    private static ScheduleItem pickTopic(List<ScheduleItem> schedules, String interest) {
        List<ScheduleItem> candidates = schedules.stream().filter(RetrospectEngine::directTopic).toList();
        List<String> keys = interestKeywords(interest);
        return candidates.stream().filter(s -> keys.stream().anyMatch(s.name()::contains)).findFirst()
                .orElseGet(() -> candidates.stream().findFirst().orElseGet(() -> schedules.stream()
                        .filter(s -> s.name() != null && !s.name().isBlank()).findFirst().orElse(null)));
    }

    private static String openingQuestion(RetrospectState state, List<ScheduleItem> schedules) {
        String who = blankName(state) ? "" : state.nickname().strip() + "님, ";
        if (state.hasSchedule() && !state.scheduleHint()) {
            return who + "오늘 " + state.schedule() + ", 어땠어요?";
        }
        String hints = schedules.stream().map(ScheduleItem::name)
                .filter(n -> n != null && !n.isBlank()).distinct().limit(3)
                .collect(Collectors.joining(", "));
        if (!hints.isEmpty()) {
            return who + "오늘 " + hints + " 일정도 있었네요. 하루 중 가장 기억에 남는 일은 뭐였어요?";
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
        if (state.awaitingTodayAction()) {
            return resolveTodayAction(state, command);
        }
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
            generateDiary(state);
            return finishComplete(state); // 그만하려는 사람은 감정 탐색 없이 일기로 끝낸다(편지 PRD 14-1)
        }

        // 단답 여부는 규칙 게이트로 감지만 한다 — 고정 문구로 되묻지 않는다(같은 문구 반복이 어색했다).
        // AI 가 만든 다음 질문으로 소재를 자연스럽게 넓히며 이어간다.
        boolean terse = AnswerGate.inspect(command.content()).isPresent();
        boolean emotionAnswered = false;
        boolean topicAnswered = false;

        String question = null;
        String empathy = null;
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
            topicAnswered = t.event() != null && !t.event().isBlank() && !t.offTopic() && !t.vague();
            state.addSecondaryEvents(t.secondaryEvents());
            state.meaning(t.meaning());
            if (t.emotionPresent()) {
                state.markEmotionSeen();
                emotionAnswered = !t.offTopic() && !t.vague();
            }
            question = t.question();
            empathy = t.empathy();
        } else {
            usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(),
                    Phase.DIARY_CHAT.key());
        }
        // 짧게 답해도 한 턴으로 센다 — 그래야 3턴 이후 체크인·5턴 종료에 제대로 도달한다.
        state.bumpDiaryTurn();

        // 3턴을 넘겨서도 단답이 이어지면 강제로 끌지 않고 "이대로 끝낼지/더 할지"를 물어 분기한다.
        boolean firstTopicAnswer = state.topicTurn() == 1 && topicAnswered;
        if (terse && !emotionAnswered && !firstTopicAnswer && !state.diaryTurnsExhausted()
                && state.diaryTurn() >= DIARY_CHECKIN_AFTER_TURN) {
            return presentDiaryCheckin(state);
        }
        if (state.diaryTurnsExhausted()) {
            return finishDiaryChat(state, empathy);
        }
        String text;
        if (state.topicTurn() == 1) {
            // 첫 소재 발화에 감정이 포함돼도 질문을 붙이지 않는다. 공감 뒤 사용자가 이어 말하게 둔다.
            text = diaryEmpathy(empathy, question, command.content());
        } else if (state.topicEmotionSeen()) {
            // 공감에 이어진 답변에서 감정이 확인되면 생각·의미 대신 다른 기억을 묻는다.
            text = diaryEmpathy(empathy, question, command.content()) + "\n" + fallbackDiaryQuestion(state);
        } else {
            text = (question != null && !question.isBlank())
                    ? breakAfterEmpathy(question.strip())
                    : diaryEmpathy(empathy, question, command.content()) + "\n" + fallbackDiaryQuestion(state);
        }
        state.addAssistantMessage(text);
        return ReplyDto.question(text, Phase.DIARY_CHAT, state.safety().level());
    }

    /** 질문은 빼되 상황을 짚는 공감 문장을 살린다. 장애 때도 맥락 없는 맞장구로 끝내지 않는다. */
    private static String diaryEmpathy(String empathy, String question, String userText) {
        String reflection = empathyStatements(empathy);
        if (reflection.isBlank()) {
            // 이전 모델 응답이 empathy를 빼먹고 question에 공감을 담은 경우도 복구한다.
            reflection = empathyStatements(question);
        }
        if (!reflection.isBlank()) {
            return reflection;
        }
        String quote = userText.strip().replaceAll("\\s+", " ").replace("?", "").replace("？", "");
        int length = Math.min(quote.codePointCount(0, quote.length()), 60);
        quote = quote.substring(0, quote.offsetByCodePoints(0, length));
        return "「" + quote + "」라고 들려주신 이야기를 소중히 듣고 있어요.\n"
                + "그 순간에 담긴 마음을 가볍게 넘기지 않고 함께할게요.";
    }

    private static String empathyStatements(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return "";
        }
        List<String> statements = new ArrayList<>();
        for (String part : candidate.strip().split("(?<=[.!?。！？])\\s*|\\R")) {
            String sentence = part.strip();
            if (sentence.contains("?") || sentence.contains("？")
                    || sentence.matches(".*(?:주세요|세요|볼까요|주실래요)[.!。！]*$")) {
                break;
            }
            if (sentence.isBlank() || Set.of("그랬군요", "그렇군요", "알겠어요", "네")
                    .contains(sentence.replaceAll("[.!。！]+$", ""))) {
                continue;
            }
            statements.add(sentence);
            if (statements.size() == 2) {
                break;
            }
        }
        return String.join(" ", statements);
    }

    /** AI 실패와 체크인 재개 시에도 현재 소재의 진행 상태와 사건 상한을 따른다. */
    private static String fallbackDiaryQuestion(RetrospectState state) {
        if (state.shouldSwitchTopic()) {
            return state.knownEventCount() >= RetrospectState.MAX_EVENTS
                    ? "지금까지 이야기한 것 중에, 다른 이야기를 나눠볼까요?"
                    : "오늘 하루 중 다른 기억에 남는 일은 뭐였어요?";
        }
        if (state.event() == null) {
            return "그때 무슨 일이 있었는지 조금만 더 들려줄래요?";
        }
        return "그 순간에는 어떤 기분이 들었어요?";
    }

    /**
     * 일기 작성 종료 → 감정 추출(대화 전체) + 일기 생성 후 <b>분기 없이</b> 감정 탐색으로 넘어간다(편지 PRD
     * 14-1). 넘어가기 전에 마지막 답변에 대한 AI 공감 한 문장이 있으면 전환 멘트 앞에 한 줄 붙인다.
     */
    private ReplyDto finishDiaryChat(RetrospectState state, String empathy) {
        generateDiary(state);
        String lead = (empathy != null && !empathy.isBlank()) ? empathy.strip() + "\n" : "";
        return startExploration(state, lead);
    }

    /**
     * 일기를 만든 뒤 감정 탐색을 시작한다. 사건이 둘이면 어느 이야기를 더 나눌지 고르고, 하나면 그 사건으로
     * 바로 감정을 묻는다. 뽑힌 사건이 없으면(추출 실패) 카드 없이 마무리한다.
     */
    private ReplyDto startExploration(RetrospectState state, String lead) {
        // 위기 안내가 나왔던 날은 「이어서 얘기하기」로 대화를 마쳤어도 카드 없이 끝낸다(편지 PRD 14-1) —
        // 그날 편지는 작은 일이 없는 조용한 편지라, 카드의 작은 행동을 채울 수 없다.
        if (state.events().isEmpty() || crisisGuidanceShown(state)) {
            return finishComplete(state);
        }
        String text = lead + EXPLORATION_TRANSITION + "\n";
        usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(), Phase.EMOTION_EXPLORATION.key());
        if (state.events().size() == 1) {
            state.selectEvent(state.events().get(0).id());
            state.enterExploration();
            return presentEmotionConfirm(state, text);
        }
        state.changePhase(Phase.AWAIT_BRANCH);
        state.awaitEventSelection();
        return presentEventSelection(state, text);
    }

    /** 대화에 위기 안내 문구가 남아 있는가 — 「이어서 얘기하기」가 안전 상태를 지워도 안내는 남는다. */
    private boolean crisisGuidanceShown(RetrospectState state) {
        return state.messages().stream().filter(Message::isAssistant).map(Message::content)
                .anyMatch(safetyPolicy::isGuidance);
    }

    static final String EXPLORATION_TRANSITION = "오늘 이야기는 일기로 정리해뒀어요. 그때 마음을 조금만 더 들여다볼게요.";

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
     * 3턴을 넘긴 뒤 단답이 나오면 강제로 끌지 않고 공감 + 「이제 정리할래요 / 조금 더 이야기할래요」를
     * 물어 분기한다. 이 물음은 일기 턴을 소모하지 않는다({@code diaryTurn} 증가 없음).
     */
    private ReplyDto presentDiaryCheckin(RetrospectState state) {
        List<Choice> options = List.of(Choice.of(WRAP_UP), Choice.of(KEEP_TALKING));
        state.lastOptions(options);
        String text = "지금까지 이야기해줘서 고마워요.\n이제 정리해 볼까요, 아니면 조금 더 이야기해볼까요?";
        state.addAssistantMessage(text + "\n" + optionLines(options));
        usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(), Phase.DIARY_CHAT.key());
        return ReplyDto.choices(text, Phase.DIARY_CHAT, toOptionDtos(options), state.safety().level());
    }

    /**
     * 단답 체크인에 대한 응답 처리. 「이제 정리할래요」면 지금까지의 이야기로 일기를 정리해 감정 탐색으로 넘어가고,
     * 그 외(「조금 더」·자유 입력)면 체크인을 접고 대화를 이어간다. 자유 입력이면 그 내용을 이번 답으로 삼는다.
     */
    private ReplyDto resolveDiaryCheckin(RetrospectState state, TurnCommand command) {
        // 「이제 정리할래요」 → 일기를 만들고 감정 · 바람으로 넘어간다(편지 PRD 14-1).
        if (picked(state, command, WRAP_UP)) {
            state.addUserMessage(WRAP_UP);
            state.lastOptions(List.of());
            state.markDiaryUserEnded();
            generateDiary(state);
            return startExploration(state, "");
        }
        // 배포 전 세션의 「일기 보러 갈래요」는 옛 방식대로 감정 탐색 없이 일기로 마무리한다.
        if (picked(state, command, GO_DIARY)) {
            state.addUserMessage(GO_DIARY);
            state.lastOptions(List.of());
            state.markDiaryUserEnded();
            generateDiary(state);
            return finishComplete(state);
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
            sb.append(state.hasSchedule() && !state.scheduleHint() ? "오늘 " + state.schedule() + "이 있었다."
                    : "오늘 하루를 돌아봤다.");
        }
        return new DiaryOutput(sb.toString().strip(), null);
    }

    // ── 분기점 · 사건 고르기 ─────────────────────────────────────────────

    /**
     * {@code AWAIT_BRANCH} 단계 — 새 세션에서는 사건 고르기만 온다. 「감정을 더 알아볼래요 / 일기 확인하러
     * 갈래요」 답은 배포 전에 분기에서 멈춘 세션만 보낸다(그때는 옛 방식대로 받는다).
     */
    private ReplyDto handleBranch(RetrospectState state, TurnCommand command) {
        if (state.awaitingEventSelection()) {
            return handleEventSelection(state, command);
        }
        Optional<Choice> chosen = resolveSingle(state, command);
        if (chosen.isPresent() && BRANCH_EXPLORE.equals(chosen.get().label())) {
            state.addUserMessage(BRANCH_EXPLORE);
            if (state.events().isEmpty()) {
                return finishComplete(state);
            }
            state.awaitEventSelection();
            return presentEventSelection(state);
        }
        // 그 외(일기 확인·미선택) → 감정 탐색 미진행이 기본값. 바람 카드 없이 마무리.
        state.addUserMessage(chosen.map(Choice::label).orElse(BRANCH_VIEW));
        return finishComplete(state);
    }

    private ReplyDto presentEventSelection(RetrospectState state) {
        return presentEventSelection(state, "");
    }

    /** 사건 고르기 — 모두 바람까지 가므로(편지 PRD 14-1) 「일기 보러 갈래요」로 빠지는 선지는 두지 않는다. */
    private ReplyDto presentEventSelection(RetrospectState state, String lead) {
        List<Choice> options = new ArrayList<>();
        for (var event : state.events()) {
            options.add(Choice.of(event.summary()));
        }
        state.lastOptions(options);
        String text = lead + "어떤 이야기를 조금 더 나눠볼까요?";
        state.addAssistantMessage(text + "\n" + optionLines(options));
        return ReplyDto.choices(text, Phase.AWAIT_BRANCH, toOptionDtos(options), state.safety().level());
    }

    private ReplyDto handleEventSelection(RetrospectState state, TurnCommand command) {
        // 옛 세션의 목록에만 「일기 보러 갈래요」가 있다.
        if (picked(state, command, GO_DIARY)) {
            state.addUserMessage(GO_DIARY);
            return finishComplete(state);
        }
        // 선지 위치를 사건 ID로 연결한다. 요약이 같아도 사건을 혼동하지 않는다.
        if (command.optionIds().size() == 1 && resolveSingle(state, command).isPresent()) {
            int index = Integer.parseInt(command.firstOption().strip()) - 1;
            if (index < state.events().size() && state.selectEvent(state.events().get(index).id())) {
                state.addUserMessage(state.selectedEvent().orElseThrow().summary());
                state.enterExploration();
                return presentEmotionConfirm(state);
            }
        }
        return presentEventSelection(state);
    }

    // ── 감정 탐색 (감정 → 바람, 2턴) ──────────────────────────────────────

    private ReplyDto handleExploration(RetrospectState state, TurnCommand command) {
        return switch (state.explorationTurn()) {
            case 0 -> handleEmotionConfirm(state, command);
            case 1 -> handleNeedConfirm(state, command);
            // 배포 전에 작은 행동 답을 기다리던 세션 — 행동은 이제 편지에서 고르므로 그대로 마무리한다.
            default -> finishComplete(state);
        };
    }

    /**
     * 1턴 — 감정 확인 질문 + 후보 제시.
     *
     * <p>선택한 사건에 연결된 추출 감정만 후보로 쓴다. 후보가 없으면 기존 입력창으로 직접 받는다.
     */
    private ReplyDto presentEmotionConfirm(RetrospectState state) {
        return presentEmotionConfirm(state, "");
    }

    private ReplyDto presentEmotionConfirm(RetrospectState state, String lead) {
        List<Choice> options = new ArrayList<>();
        for (Emotion e : distinctNormalized(state.selectedEventEmotions())) {
            options.add(Choice.of(e.label()));
        }
        state.lastOptions(options);
        // 무엇에 대한 질문인지 이름을 밝힌다 — "그 일"만 두면 사용자는 직전 대화를 떠올릴 수밖에 없다.
        String text = lead + state.selectedEvent()
                .map(e -> emotionConfirmQuestion(e.summary()))
                .orElse("오늘 하루를 떠올렸을 때, 지금 가장 가까운 감정은 무엇인가요?");
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
        // 직접 입력이 고정 욕구 단어면 연결표에 사용하고, 원문도 '바랐던 모습'으로 보존한다.
        if (command.hasContent()) {
            Needs.byWord(command.content().strip()).ifPresent(chosen::add);
            state.desiredState(clamp(command.content()));
        }
        state.chooseNeeds(limit1(chosen));
        state.addUserMessage(chosen.isEmpty()
                ? (command.hasContent() ? clamp(command.content()) : NOT_SURE)
                : needWordsOf(chosen));
        state.bumpExplorationTurn();
        // 바람에서 끝낸다 — 작은 행동은 다음 날 아침 편지에서 고른다(편지 PRD 14-1).
        return finishComplete(state);
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
        String text = card != null
                ? "오늘 이야기를 정리해뒀어요. 내일 아침엔 이 마음에 맞는 작은 일을 편지로 골라 올게요."
                : "오늘 이야기를 정리해뒀어요. 일기에서 천천히 확인해봐요.";
        state.addAssistantMessage(text);
        return ReplyDto.completed(text, diary, card, state.safety().level());
    }

    /**
     * 사건 이름 + 을/를 — 조사는 이름의 마지막 글자로 고른다("개발을", "팀 발표를").
     * 이름을 괄호로 감싸지 않는다: 「」 는 채팅 말투에 비해 딱딱한 인용 기호라 시스템이 끼워 넣은
     * 티가 났다(2026-10-13).
     */
    private static String emotionConfirmQuestion(String label) {
        return label + Josa.pick(label, "을", "를")
                + " 떠올렸을 때, 지금 가장 가까운 감정은 무엇인가요?";
    }

    /**
     * 상태의 감정 탐색 슬롯으로 바람 카드를 만든다.
     *
     * <p>상황은 <b>감정 확인 질문과 같은 사건</b>({@link RetrospectState#mainEvent()})을 가리킨다 —
     * 어긋나면 사용자가 A 를 떠올리고 답했는데 카드에는 B 가 적힌다.
     */
    private static ReplyDto.WishCardDto buildWishCard(RetrospectState state) {
        String situation = state.cardSituation();
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

    /**
     * 「이어서 얘기하기」 — 위기 안내로 멈추기 전 phase 로 돌아간다. 들어온 입력을 답변으로 처리하지
     * 않고, <b>멈추기 직전에 보던 질문을 한 번 더 보여준다</b>. 위기 표현이 곧 답변으로 굳어버리면
     * 사용자가 다시 답할 기회를 잃고, 맥 끊긴 채로 대화가 이어지기 때문이다.
     *
     * <p>직전 질문을 찾지 못하는 드문 경우(히스토리가 비정상)엔 예전처럼 phase 에 맞춰 이어간다.
     */
    private ReplyDto handleSafetyResume(RetrospectState state, TurnCommand command) {
        Phase resumed = state.resumeFromHold();
        String question = lastQuestionBeforeHold(state);
        if (question != null && !question.isBlank()) {
            state.addAssistantMessage(question);
            return ReplyDto.question(question, resumed, state.safety().level());
        }
        return switch (resumed) {
            case AWAIT_BRANCH -> handleBranch(state, command);
            case EMOTION_EXPLORATION -> handleExploration(state, command);
            default -> handleDiaryTurn(state, command);
        };
    }

    /**
     * 위기 안내로 멈추기 직전 사용자가 답하던 질문 — 마지막 사용자 발화 바로 앞의 AI 발화다.
     * (히스토리는 … 질문(AI) · 위기 발화(사용자) · 안내(AI) 순이라, 마지막 사용자 발화 앞을 본다.)
     */
    private static String lastQuestionBeforeHold(RetrospectState state) {
        List<Message> messages = state.messages();
        int lastUser = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).isUser()) {
                lastUser = i;
                break;
            }
        }
        for (int i = lastUser - 1; i >= 0; i--) {
            if (messages.get(i).isAssistant()) {
                return messages.get(i).content();
            }
        }
        return null;
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
            String id = String.valueOf(i + 1);
            out.add(new ReplyDto.OptionDto(id, c.label(), c.description(), null,
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
            "그만", "그만할래", "그만할게", "이제 그만", "더 말할 건 없",
            "여기까지", "끝낼래", "마칠래", "그만하고 싶");

    private static boolean isStopRequest(String content) {
        if (content == null) {
            return false;
        }
        String t = content.strip();
        // '취소됐어요/잘됐어요' 같은 사건 설명을 '됐어요'라는 종료 답변으로 오인하지 않는다.
        return t.length() <= 15 && (STOP_PHRASES.stream().anyMatch(t::contains)
                || t.matches("(?:(?:이제|이젠)\\s*)?됐(?:어|어요)[.!。！]*"));
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
