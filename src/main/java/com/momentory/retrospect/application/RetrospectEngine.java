package com.momentory.retrospect.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /** 대화로 이어갈 개인화 소재 하나 — 관심분야가 담긴 일정 우선, 없으면 첫 번째(없으면 null). */
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
        return schedules.get(0);
    }

    private static String openingQuestion(RetrospectState state) {
        String who = blankName(state) ? "" : state.nickname().strip() + "님, ";
        if (state.hasSchedule()) {
            return who + "오늘 " + state.schedule() + " 일정이 있었던 것 같은데, 실제로 진행됐어요?";
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
            return finishDiaryChat(state);
        }

        // 규칙 게이트: 명백한 비답변 → 되묻기(캡 안에서). 일기 턴을 소모하지 않는다.
        Optional<AnswerGate.HoldReason> ruleHold = AnswerGate.inspect(command.content());
        if (ruleHold.isPresent() && state.reasks() < reaskCap) {
            state.bumpReask();
            return holdReply(state, Phase.DIARY_CHAT, ruleHold.get());
        }
        boolean mustAdvance = state.reasks() >= reaskCap;

        String question;
        Optional<DiaryTurn> ai = diaryChatAssistant.turn(state, command.content());
        if (ai.isPresent()) {
            DiaryTurn t = ai.get();
            mergeSafety(state, t.safetyLevelOrNone(), t.safetyFlags(), userMessage.id());
            if (state.safety().level().stopsRetrospect()) {
                return safetyReply(state);
            }
            if ((t.offTopic() || t.vague()) && !mustAdvance) {
                state.bumpReask();
                return holdReply(state, Phase.DIARY_CHAT, holdReasonFor(t.offTopic()));
            }
            state.event(t.event());
            state.addSecondaryEvents(t.secondaryEvents());
            state.meaning(t.meaning());
            if (t.emotionPresent()) {
                state.markEmotionSeen();
            }
            question = t.question();
        } else {
            usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(),
                    Phase.DIARY_CHAT.key());
            question = null;
        }
        state.bumpDiaryTurn();
        state.resetReask();

        if (state.diarySlotsComplete() || state.diaryTurnsExhausted()) {
            return finishDiaryChat(state);
        }
        String text = (question != null && !question.isBlank())
                ? breakAfterEmpathy(question.strip())
                : fallbackDiaryQuestion(state);
        state.addAssistantMessage(text);
        return ReplyDto.question(text, Phase.DIARY_CHAT, state.safety().level());
    }

    /** 다음 빈 슬롯을 겨눈 폴백 질문 — AI 실패 시. */
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
        return "조금 더 이야기해 주고 싶은 게 있을까요?";
    }

    /** 일기 작성 종료 → 감정 추출(대화 전체) + 일기 생성 후 분기점 제시. */
    private ReplyDto finishDiaryChat(RetrospectState state) {
        state.emotions(emotionExtractor.extract(state));
        DiaryOutput out = diaryWriter.write(state).orElseGet(() -> fallbackDiary(state));
        state.diaryDraft(out.diary());

        state.changePhase(Phase.AWAIT_BRANCH);
        List<Choice> options = List.of(Choice.of(BRANCH_EXPLORE), Choice.of(BRANCH_VIEW));
        state.lastOptions(options);
        String text = "오늘 이야기는 일기로 정리해뒀어요. 지금 느낀 감정을 조금 더 알아볼까요?";
        state.addAssistantMessage(text + "\n" + optionLines(options));
        usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(), Phase.AWAIT_BRANCH.key());
        return ReplyDto.choices(text, Phase.AWAIT_BRANCH, toOptionDtos(options),
                state.safety().level());
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

    /** 1턴 — 감정 확인 질문 + 후보(추출 감정) 제시. */
    private ReplyDto presentEmotionConfirm(RetrospectState state) {
        List<Choice> options = new ArrayList<>();
        for (Emotion e : distinctNormalized(state.emotions())) {
            options.add(Choice.of(e.label()));
        }
        options.add(Choice.input(WRITE_OWN));
        options.add(Choice.of(NOT_SURE));
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
        // 직접 적기 텍스트도 감정으로 정규화 시도(실패해도 진행).
        if (command.hasContent()) {
            Emotion.fromLabel(command.content().strip()).ifPresent(chosen::add);
        }
        state.confirmEmotions(limit2(chosen));
        state.addUserMessage(chosen.isEmpty() ? NOT_SURE : labelsOf(chosen));
        state.bumpExplorationTurn();
        return presentNeedConfirm(state);
    }

    /** 2턴 — 바람 확인 질문(감정 성격별 워딩) + 고정 욕구 후보. */
    private ReplyDto presentNeedConfirm(RetrospectState state) {
        List<Need> suggested = explorationAssistant.suggestNeeds(state);
        if (suggested.isEmpty()) {
            suggested = Needs.ALL.subList(0, 4);
        }
        List<Choice> options = new ArrayList<>();
        for (Need n : suggested.stream().limit(4).toList()) {
            options.add(Choice.of(n.word(), n.meaning()));
        }
        options.add(Choice.input(WRITE_OWN));
        options.add(Choice.of(NOT_SURE));
        state.lastOptions(options);
        String text = WishSentiment.of(state.confirmedEmotions()).isPositive()
                ? "그때 오늘 내 마음을 채워준 것은 무엇에 가까웠을까요?"
                : "그때 내 마음이 바랐던 것은 무엇에 가까웠을까요?";
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
        state.chooseNeeds(limit2(chosen));
        // 직접 적은 텍스트는 '바랐던 모습'으로 담는다.
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
        options.add(Choice.input(WRITE_OWN));
        options.add(Choice.of(HERE_END));
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
        ReplyDto.DiaryDto diary = new ReplyDto.DiaryDto(null, state.diaryDraft(), null);
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
        return new ReplyDto.WishCardDto(null, situation, emotions, needs, state.desiredState(),
                state.smallAction(), sentiment);
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
