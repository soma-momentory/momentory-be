package com.momentory.retrospect.application;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.momentory.retrospect.application.metering.UsageRecorder;
import com.momentory.retrospect.application.event.CrisisDetected;
import com.momentory.retrospect.application.event.RetrospectCompleted;
import com.momentory.retrospect.domain.assistant.DiaryOutput;
import com.momentory.retrospect.domain.assistant.DiaryWriter;
import com.momentory.retrospect.domain.assistant.TurnScript;
import com.momentory.retrospect.domain.assistant.TurnScripter;
import com.momentory.retrospect.domain.assistant.UnderstandingCheck;
import com.momentory.retrospect.domain.assistant.UnderstandingChecker;
import com.momentory.retrospect.domain.AnswerGate;
import com.momentory.retrospect.domain.Message;
import com.momentory.retrospect.domain.Phase;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.ScheduleItem;
import com.momentory.retrospect.domain.SchedulePicker;
import com.momentory.retrospect.domain.safety.Guidance;
import com.momentory.retrospect.domain.safety.PromptGuard;
import com.momentory.retrospect.domain.safety.SafetyLevel;
import com.momentory.retrospect.domain.safety.SafetyPolicy;
import com.momentory.retrospect.domain.script.Direction;
import com.momentory.retrospect.domain.script.MeasureField;
import com.momentory.retrospect.domain.script.OptionItem;
import com.momentory.retrospect.domain.script.RetroMode;
import com.momentory.retrospect.domain.script.ScriptStep;
import com.momentory.retrospect.domain.script.Scripts;
import com.momentory.retrospect.domain.script.SubDirection;
import com.momentory.retrospect.infrastructure.ai.LlmRole;

/**
 * 회고 대화 엔진 — 스크립트 상태 머신 (채팅 최적 시나리오 PDF).
 *
 * <pre>
 * intro(1턴 공통 질문) → 이해 확인 + 방향 4택 → (세부 2택) → 모드 스크립트 → 행동 카드·일기
 *                                                                       ↘ ended (안전 중단)
 * </pre>
 *
 * <p><b>무상태 싱글턴이다.</b> 모든 세션 상태는 인자로 받은 {@link RetrospectState} 안에 있다.
 *
 * <p>흐름은 전부 규칙(스크립트)이 결정한다. AI는 문구를 다듬을 뿐이고, 실패하면 PDF 원형
 * 문구로 폴백된다. 유료 호출은 텍스트/선택지 턴당 1회 + 일기 1회.
 */
@Component
public class RetrospectEngine {

    private static final DateTimeFormatter CARD_DATE =
            DateTimeFormatter.ofPattern("yyyy년 M월 d일");

    private final SafetyPolicy safetyPolicy;
    private final UnderstandingChecker understandingChecker;
    private final TurnScripter turnScripter;
    private final DiaryWriter diaryWriter;
    private final UsageRecorder usage;
    private final ApplicationEventPublisher events;

    /**
     * 텍스트 답변 게이트의 스텝당 되묻기 상한(STEP 11~12). 이 횟수까지만 흐름을 붙잡고,
     * 넘으면 판정과 무관하게 강제 전진한다 — 비결정성을 봉쇄해 상태 머신을 결정적으로 유지한다.
     * {@code <= 0} 이면 게이트 사실상 off(현행 동작 = baseline).
     */
    private final int reaskCap;

    public RetrospectEngine(SafetyPolicy safetyPolicy, UnderstandingChecker understandingChecker,
            TurnScripter turnScripter, DiaryWriter diaryWriter, UsageRecorder usage,
            ApplicationEventPublisher events,
            @Value("${momentory.gate.reask-cap:1}") int reaskCap) {
        this.safetyPolicy = safetyPolicy;
        this.understandingChecker = understandingChecker;
        this.turnScripter = turnScripter;
        this.diaryWriter = diaryWriter;
        this.usage = usage;
        this.events = events;
        this.reaskCap = reaskCap;
    }

    // ── 시작 — 1턴 공통 질문(템플릿, AI 0회) ─────────────────────────────

    public ReplyDto start(RetrospectState state, StartCommand command) {
        // 일정이 없으면 특정 일정 없이 현재 감정 하나로 '오늘 하루'를 연다(관심분야로 질문 구체화).
        if (command.schedules().isEmpty()) {
            state.beginNoSchedule(command.currentEmotion(), command.nickname(), command.interest());
            return askFirstQuestion(state, null);
        }

        // 일정이 여러 개면 규칙(현재 감정 매칭 → 관심분야 매칭)으로 대상을 고른다.
        Optional<ScheduleItem> picked = SchedulePicker.pick(command.schedules(),
                command.currentEmotion(), command.interest());

        if (picked.isEmpty()) {
            // 규칙으로 못 골랐다 — 사용자에게 선택권을 준다 (AI 0회).
            state.beginPendingSchedules(command.schedules(), command.currentEmotion(),
                    command.nickname(), command.interest());
            String text = Scripts.scheduleChoiceQuestion(command.nickname());
            List<ReplyDto.OptionDto> options = scheduleOptions(command.schedules());
            state.addAssistantMessage(text + "\n" + optionLines(options));
            usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(),
                    Phase.AWAIT_SCHEDULE.key());
            return ReplyDto.choices(text, Phase.AWAIT_SCHEDULE, options, state.safety().level());
        }

        state.beginPendingSchedules(command.schedules(), command.currentEmotion(),
                command.nickname(), command.interest());
        return askFirstQuestion(state, picked.get());
    }

    /** 1턴 공통 질문(템플릿)을 내보낸다. {@code schedule} 이 null 이면 일정 없는 회고다. */
    private ReplyDto askFirstQuestion(RetrospectState state, ScheduleItem schedule) {
        String text;
        if (schedule != null) {
            state.assignSchedule(schedule);
            text = Scripts.firstQuestion(state.nickname(), schedule.name(),
                    schedule.emotion(), state.currentEmotion());
        } else {
            text = Scripts.firstQuestionNoSchedule(state.nickname(), state.currentEmotion(),
                    state.interest());
        }
        state.addAssistantMessage(text);
        usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(), Phase.INTRO.key());

        return ReplyDto.question(text, Phase.INTRO, state.safety().level());
    }

    // ── 턴 처리 진입점 ───────────────────────────────────────────────────

    public ReplyDto handle(RetrospectState state, TurnCommand command) {
        Phase phase = state.phase();
        if (phase.isTerminal()) {
            return ReplyDto.alreadyFinished(phase);
        }
        return switch (phase) {
            case AWAIT_SCHEDULE -> handleScheduleChoice(state, command);
            case INTRO -> handleFirstAnswer(state, command);
            case AWAIT_DIRECTION -> handleDirection(state, command);
            case AWAIT_SUB_DIRECTION -> handleSubDirection(state, command);
            case SCRIPT -> handleScriptTurn(state, command);
            default -> ReplyDto.alreadyFinished(phase);
        };
    }

    // ── 일정 선택(다중 일정에서 규칙으로 못 골랐을 때만) ─────────────────

    private ReplyDto handleScheduleChoice(RetrospectState state, TurnCommand command) {
        List<ScheduleItem> candidates = state.pendingSchedules();
        String raw = command.hasOption() ? command.optionId() : command.content();
        Optional<ScheduleItem> chosen = raw == null
                ? Optional.empty()
                : indexOf(raw, candidates.size()).map(candidates::get);

        if (chosen.isEmpty()) {
            return ReplyDto.choices("이야기할 일정을 골라주세요.", Phase.AWAIT_SCHEDULE,
                    scheduleOptions(candidates), state.safety().level());
        }
        state.addUserMessage(chosen.get().name());
        return askFirstQuestion(state, chosen.get());
    }

    // ── 1턴 답변 → 이해 확인 + 방향 4택 ──────────────────────────────────

    private ReplyDto handleFirstAnswer(RetrospectState state, TurnCommand command) {
        if (!command.hasContent()) {
            return ReplyDto.question("편하게, 떠오르는 대로 적어주셔도 괜찮아요.", Phase.INTRO,
                    state.safety().level());
        }
        Message userMessage = state.addUserMessage(command.content());

        // 규칙 층 안전이 먼저다. imminent 면 AI 를 부르지 않고 단락시킨다.
        SafetyPolicy.ScanResult scan = safetyPolicy.scan(command.content());
        mergeSafety(state, scan.level(), scan.flags(), userMessage.id());
        if (state.safety().level() == SafetyLevel.IMMINENT) {
            return safetyReply(state);
        }

        // 구현 공개 요청·프롬프트 공격은 규칙 층에서 흘려보낸다 — AI 없이 회고로 되돌린다.
        Optional<PromptGuard.Category> attack = PromptGuard.inspect(command.content());
        if (attack.isPresent()) {
            return deflectReply(state, Phase.INTRO, attack.get());
        }

        // Layer 1(규칙 게이트): 명백한 비답변이면 AI 없이 되묻는다(캡 안에서).
        Optional<AnswerGate.HoldReason> ruleHold = AnswerGate.inspect(command.content());
        if (ruleHold.isPresent() && state.reasks() < reaskCap) {
            state.bumpReask();
            return holdReply(state, Phase.INTRO, ruleHold.get());
        }
        boolean mustAdvance = state.reasks() >= reaskCap;

        // AI-G1: 이해 확인. 실패해도 고정 공감문으로 흐름은 이어진다.
        String reflection;
        Optional<UnderstandingCheck> check = understandingChecker.check(state, command.content());
        if (check.isPresent()) {
            UnderstandingCheck u = check.get();
            mergeSafety(state, u.safetyLevelOrNone(), u.safetyFlags(), userMessage.id());
            if (state.safety().level().stopsRetrospect()) {
                return safetyReply(state);
            }
            // Layer 2: 첫 질문과 무관하거나(offTopic) 얼버무린(vague) 답이면 방향 선택으로
            // 넘기지 않고 한 번 되묻는다 — 이탈은 비답변 멘트로, 얼버무림은 발판 멘트로.
            if ((u.offTopic() || u.vague()) && !mustAdvance) {
                state.bumpReask();
                return holdReply(state, Phase.INTRO, holdReasonFor(u.offTopic()));
            }
            reflection = u.reflection().strip();
            state.situationSummary(u.situation());
        } else {
            reflection = Scripts.understandingFallback(state.currentEmotion());
            usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(),
                    state.phase().key());
        }

        state.recordAnswer("first_moment", command.content());
        state.resetReask();
        state.changePhase(Phase.AWAIT_DIRECTION);
        // 이해 확인(공감)과 질문은 줄을 나눈다.
        String text = reflection + "\n" + Scripts.DIRECTION_QUESTION;
        state.addAssistantMessage(text + "\n" + optionLines(directionOptions()));

        return ReplyDto.choices(text, Phase.AWAIT_DIRECTION, directionOptions(),
                state.safety().level());
    }

    // ── 방향 선택 ────────────────────────────────────────────────────────

    private ReplyDto handleDirection(RetrospectState state, TurnCommand command) {
        Optional<Direction> direction = parseDirection(command);
        if (direction.isEmpty()) {
            return ReplyDto.choices("원하시는 방향을 골라주세요.", Phase.AWAIT_DIRECTION,
                    directionOptions(), state.safety().level());
        }
        state.addUserMessage(direction.get().label());

        if (direction.get() == Direction.PERSPECTIVE) {
            state.changePhase(Phase.AWAIT_SUB_DIRECTION);
            String text = Scripts.subDirectionQuestion(state.nickname());
            state.addAssistantMessage(text + "\n" + optionLines(subDirectionOptions()));
            usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(),
                    state.phase().key());
            return ReplyDto.choices(text, Phase.AWAIT_SUB_DIRECTION, subDirectionOptions(),
                    state.safety().level());
        }
        return enterMode(state, direction.get().mode());
    }

    private ReplyDto handleSubDirection(RetrospectState state, TurnCommand command) {
        Optional<SubDirection> sub = parseSubDirection(command);
        if (sub.isEmpty()) {
            return ReplyDto.choices("더 가까운 쪽을 골라주세요.", Phase.AWAIT_SUB_DIRECTION,
                    subDirectionOptions(), state.safety().level());
        }
        state.addUserMessage(sub.get().label());
        return enterMode(state, sub.get().mode());
    }

    private ReplyDto enterMode(RetrospectState state, RetroMode mode) {
        state.applyMode(mode);
        return presentNext(state, null);
    }

    // ── 스크립트 턴 ──────────────────────────────────────────────────────

    private ReplyDto handleScriptTurn(RetrospectState state, TurnCommand command) {
        ScriptStep step = state.currentStep().orElse(null);
        if (step == null) {
            return presentNext(state, null); // 방어 — 정상 흐름에선 오지 않는다.
        }
        return switch (step.kind()) {
            case MEASURE -> handleMeasureAnswer(state, step, command);
            case CHOICE -> handleChoiceAnswer(state, step, command);
            case TEXT -> handleTextAnswer(state, step, command);
        };
    }

    private ReplyDto handleMeasureAnswer(RetrospectState state, ScriptStep step,
            TurnCommand command) {
        if (!command.hasMeasures()) {
            return ReplyDto.measure("슬라이더로 표시해 주시면 이어갈게요.", Phase.SCRIPT,
                    measureDtos(state, step), state.safety().level());
        }
        List<String> parts = new ArrayList<>();
        for (MeasureField field : effectiveMeasures(state, step)) {
            Integer value = command.measures().get(field.key());
            if (value != null) {
                state.recordMeasure(step.id(), field.key(), value);
                parts.add(measureLabel(state, step, field) + " " + Math.max(0, Math.min(10, value)));
            }
        }
        state.addUserMessage(String.join(" / ", parts));
        return presentNext(state, null);
    }

    private ReplyDto handleChoiceAnswer(RetrospectState state, ScriptStep step,
            TurnCommand command) {
        Optional<OptionItem> chosen = command.hasOption()
                ? state.resolveOption(command.optionId())
                : Optional.empty();
        if (chosen.isEmpty()) {
            return ReplyDto.choices("보기 중 하나를 골라주세요.", Phase.SCRIPT,
                    toOptionDtos(state.lastOptions()), state.safety().level());
        }
        state.recordAnswer(step.id(), chosen.get().toLine());
        if (step.actionStep()) {
            state.chooseAction(chosen.get());
        }
        state.addUserMessage(chosen.get().label());
        return presentNext(state, null);
    }

    private ReplyDto handleTextAnswer(RetrospectState state, ScriptStep step, TurnCommand command) {
        if (!command.hasContent()) {
            return ReplyDto.question("편하게, 떠오르는 대로 적어주셔도 괜찮아요.", Phase.SCRIPT,
                    state.safety().level());
        }
        Message userMessage = state.addUserMessage(command.content());

        SafetyPolicy.ScanResult scan = safetyPolicy.scan(command.content());
        mergeSafety(state, scan.level(), scan.flags(), userMessage.id());
        if (state.safety().level() == SafetyLevel.IMMINENT) {
            return safetyReply(state);
        }

        // 구현 공개 요청·프롬프트 공격은 규칙 층에서 흘려보낸다 — 기록·전진 없이 회고로 되돌린다.
        Optional<PromptGuard.Category> attack = PromptGuard.inspect(command.content());
        if (attack.isPresent()) {
            return deflectReply(state, Phase.SCRIPT, attack.get());
        }

        // Layer 1(규칙 게이트): 명백한 비답변이면 AI 없이 되묻는다(캡 안에서). 기록·전진 없음.
        Optional<AnswerGate.HoldReason> ruleHold = AnswerGate.inspect(command.content());
        if (ruleHold.isPresent() && state.reasks() < reaskCap) {
            state.bumpReask();
            return holdReply(state, Phase.SCRIPT, ruleHold.get());
        }

        // 다음 스텝이 측정(템플릿, AI 0회)이거나 종료면 판정을 걸지 않는다 — 그 전이엔 원래 G2 가
        // 없어서, 판정을 위해 호출을 새로 만들면 비용 중립이 깨진다. Layer 1 만으로 충분하다.
        Optional<ScriptStep> next = state.peekNext();
        boolean judgeable = next.isPresent() && !next.get().isMeasure();
        if (!judgeable) {
            state.recordAnswer(step.id(), command.content());
            state.resetReask();
            return presentNext(state, userMessage.id());
        }

        // Layer 2: 다음 스텝을 내보낼 때 어차피 도는 G2(1회)에 얹은 offTopic 판정으로 전진을 정한다.
        boolean mustAdvance = state.reasks() >= reaskCap;
        Optional<TurnScript> ai = turnScripter.script(state, next.get());
        if (ai.isPresent()) {
            mergeSafety(state, ai.get().safetyLevelOrNone(), ai.get().safetyFlags(),
                    userMessage.id());
            if (state.safety().level().stopsRetrospect()) {
                return safetyReply(state);
            }
            if ((ai.get().offTopic() || ai.get().vague()) && !mustAdvance) {
                state.bumpReask();
                return holdReply(state, Phase.SCRIPT, holdReasonFor(ai.get().offTopic()));
            }
        }
        state.recordAnswer(step.id(), command.content());
        state.commitAdvance();
        state.resetReask();
        return present(state, next.get(), ai);
    }

    // ── 다음 스텝 내보내기 ───────────────────────────────────────────────

    /**
     * 다음 스텝을 화면에 내보낸다. 측정이면 템플릿(AI 0회), 텍스트·선택지면 AI 1회(실패 시 폴백).
     *
     * @param lastUserMsgId AI 안전 판정을 걸 직전 사용자 메시지 id (없으면 null)
     */
    private ReplyDto presentNext(RetrospectState state, String lastUserMsgId) {
        Optional<ScriptStep> next = state.advanceStep();
        if (next.isEmpty()) {
            return finish(state);
        }
        return presentFetching(state, next.get(), lastUserMsgId);
    }

    /** 스텝을 낸다 — 측정이 아니면 여기서 G2 를 부르고 안전을 병합한 뒤 {@link #present} 로 넘긴다. */
    private ReplyDto presentFetching(RetrospectState state, ScriptStep step, String lastUserMsgId) {
        if (step.isMeasure()) {
            return present(state, step, Optional.empty());
        }
        Optional<TurnScript> ai = turnScripter.script(state, step);
        if (ai.isPresent()) {
            mergeSafety(state, ai.get().safetyLevelOrNone(), ai.get().safetyFlags(), lastUserMsgId);
            if (state.safety().level().stopsRetrospect()) {
                return safetyReply(state);
            }
        }
        return present(state, step, ai);
    }

    /**
     * 이미 해석된 G2 결과({@code ai}, empty 면 PDF 원형 폴백)로 스텝을 내보낸다.
     * 안전 병합은 호출자 책임이다(측정은 AI 0회라 해당 없음). 게이트가 판정 후 재사용한다.
     */
    private ReplyDto present(RetrospectState state, ScriptStep step, Optional<TurnScript> ai) {
        if (step.isMeasure()) {
            // 일정이 없으면 일정 감정 슬라이더가 빠지므로, 두 감정을 언급하는 기본 문구 대신
            // 현재 감정 하나만 묻는 문구를 쓴다.
            String measureText = state.hasSchedule()
                    ? fill(state, step.fallbackText())
                    : Scripts.measurePromptSingleEmotion(state.currentEmotion(),
                            effectiveMeasures(state, step).contains(MeasureField.BELIEF));
            state.addAssistantMessage(measureText);
            usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(),
                    state.phase().key());
            return ReplyDto.measure(measureText, Phase.SCRIPT, measureDtos(state, step),
                    state.safety().level());
        }

        String text;
        List<OptionItem> options;
        if (ai.isPresent()) {
            // 프롬프트 지시만으로는 줄바꿈이 안정적이지 않아 서버가 강제한다.
            text = breakAfterEmpathy(ai.get().message().strip());
            options = step.isChoice() ? ai.get().options() : List.of();
        } else {
            text = fill(state, step.fallbackText());
            options = step.fallbackOptions();
            usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(),
                    state.phase().key());
        }

        if (step.isChoice()) {
            state.lastOptions(options);
            // 대화 로그에는 보기까지 남긴다 — 다음 턴 프롬프트가 이 로그를 그대로 본다.
            state.addAssistantMessage(text + "\n" + optionLines(toOptionDtos(options)));
            return ReplyDto.choices(text, Phase.SCRIPT, toOptionDtos(options),
                    state.safety().level());
        }
        state.addAssistantMessage(text);
        return ReplyDto.question(text, Phase.SCRIPT, state.safety().level());
    }

    /** Layer 2 판정 → 되묻기 이유. 완전 이탈은 비답변 멘트, 얼버무림은 발판 멘트로 나눈다. */
    private static AnswerGate.HoldReason holdReasonFor(boolean offTopic) {
        return offTopic ? AnswerGate.HoldReason.NON_ANSWER : AnswerGate.HoldReason.SOFT_EVASION;
    }

    /** 게이트 되묻기 — 전진·기록 없이 같은 스텝(또는 intro)에 머문다. AI 미호출(스크립트 대체). */
    private ReplyDto holdReply(RetrospectState state, Phase phase, AnswerGate.HoldReason reason) {
        String text = AnswerGate.message(reason);
        state.addAssistantMessage(text);
        usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(), phase.key());
        return ReplyDto.question(text, phase, state.safety().level());
    }

    /**
     * 프롬프트 가드 되돌리기 — 구현 공개 요청·공격에 정보 없이 회고로 되돌린다.
     * 답변으로 기록하지 않고 전진하지 않는다. AI 미호출(공격 판정에 AI 를 쓰지 않는다).
     */
    private ReplyDto deflectReply(RetrospectState state, Phase phase, PromptGuard.Category category) {
        String text = PromptGuard.message(category);
        state.addAssistantMessage(text);
        usage.recordPoolSubstitution(state.id(), LlmRole.G2_FALLBACK.key(), phase.key());
        return ReplyDto.question(text, phase, state.safety().level());
    }

    // ── 종료 — 행동 카드 + 일기 ──────────────────────────────────────────

    private ReplyDto finish(RetrospectState state) {
        state.changePhase(Phase.COMPLETE);

        ReplyDto.ActionCardDto card = null;
        if (state.mode().hasActionCard() && state.chosenAction() != null) {
            String situation = state.situationSummary() != null
                    ? state.situationSummary()
                    : state.hasSchedule() ? state.schedule() + "에서 있었던 일" : "오늘 하루 있었던 일";
            card = new ReplyDto.ActionCardDto(situation, state.chosenAction().label(),
                    state.chosenAction().description(), LocalDate.now().format(CARD_DATE));
        }

        // AI-G4: 일기 생성 (한 호출). 실패하면 답변 기록으로 최소한의 일기를 조립한다.
        DiaryOutput out = diaryWriter.write(state).orElseGet(() -> fallbackDiary(state));
        ReplyDto.DiaryDto diary = new ReplyDto.DiaryDto(out.diary(),
                state.mode().hasReframedDiary() ? out.reframedDiary() : null);

        String text = card != null
                ? "오늘 회고를 마쳤어요. 아래에 행동 카드와 오늘의 일기를 남겨둘게요."
                : "오늘 회고를 마쳤어요. 아래에 오늘의 일기를 남겨둘게요.";
        state.addAssistantMessage(text);
        events.publishEvent(new RetrospectCompleted(state.id(), state.mode().key(), diary));

        return ReplyDto.completed(text, diary, card, state.safety().level());
    }

    /** AI 없이 답변 기록만으로 만든 최소한의 일기 — 일기 없이 끝나는 것보다는 낫다. */
    private DiaryOutput fallbackDiary(RetrospectState state) {
        String first = state.answers().getOrDefault("first_moment", "");
        String cur = state.currentEmotion() == null ? "복잡" : state.currentEmotion().stem();
        String diary = state.hasSchedule()
                ? "오늘 %s에서 %s함을 느꼈고, 지금은 %s함이 남아 있다. %s".formatted(
                        state.schedule(),
                        state.scheduleEmotion() == null ? "복잡" : state.scheduleEmotion().stem(),
                        cur, first)
                : "오늘 하루, %s함이 남아 있다. %s".formatted(cur, first);
        return new DiaryOutput(diary.strip(), null);
    }

    /**
     * 공감 문장(들)과 질문 사이에 줄바꿈을 넣는다 — "받아주기. 질문?"이 한 덩어리로 붙어
     * 읽기 힘들다는 피드백 반영.
     *
     * <p>이미 줄바꿈이 있으면 그대로 둔다. 물음표가 있으면 그 앞의 마지막 문장 경계에서,
     * 없으면 첫 문장 경계에서 나눈다(공감이 여러 문장이어도 질문만 아랫줄로 간다).
     */
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

    // ── 방향 파싱·선택지 변환 ────────────────────────────────────────────

    private Optional<Direction> parseDirection(TurnCommand command) {
        String raw = command.hasOption() ? command.optionId() : command.content();
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Optional<Direction> byKey = Direction.fromKey(raw.strip());
        if (byKey.isPresent()) {
            return byKey;
        }
        return indexOf(raw, Direction.values().length).map(i -> Direction.values()[i]);
    }

    private Optional<SubDirection> parseSubDirection(TurnCommand command) {
        String raw = command.hasOption() ? command.optionId() : command.content();
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Optional<SubDirection> byKey = SubDirection.fromKey(raw.strip());
        if (byKey.isPresent()) {
            return byKey;
        }
        return indexOf(raw, SubDirection.values().length).map(i -> SubDirection.values()[i]);
    }

    private static Optional<Integer> indexOf(String raw, int bound) {
        try {
            int idx = Integer.parseInt(raw.strip()) - 1;
            return idx >= 0 && idx < bound ? Optional.of(idx) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static List<ReplyDto.OptionDto> directionOptions() {
        List<ReplyDto.OptionDto> out = new ArrayList<>();
        Direction[] values = Direction.values();
        for (int i = 0; i < values.length; i++) {
            out.add(new ReplyDto.OptionDto(String.valueOf(i + 1), values[i].label(),
                    values[i].typeName()));
        }
        return out;
    }

    /** 일정 후보를 선택지로 — description 에 그 일정의 감정 라벨을 보여준다. */
    private static List<ReplyDto.OptionDto> scheduleOptions(List<ScheduleItem> schedules) {
        List<ReplyDto.OptionDto> out = new ArrayList<>();
        for (int i = 0; i < schedules.size(); i++) {
            ScheduleItem s = schedules.get(i);
            out.add(new ReplyDto.OptionDto(String.valueOf(i + 1), s.name(),
                    s.emotion() == null ? null : s.emotion().label()));
        }
        return out;
    }

    private static List<ReplyDto.OptionDto> subDirectionOptions() {
        List<ReplyDto.OptionDto> out = new ArrayList<>();
        SubDirection[] values = SubDirection.values();
        for (int i = 0; i < values.length; i++) {
            out.add(new ReplyDto.OptionDto(String.valueOf(i + 1), values[i].label(),
                    values[i].typeName()));
        }
        return out;
    }

    private static List<ReplyDto.OptionDto> toOptionDtos(List<OptionItem> options) {
        List<ReplyDto.OptionDto> out = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            OptionItem o = options.get(i);
            out.add(new ReplyDto.OptionDto(String.valueOf(i + 1), o.label(), o.description()));
        }
        return out;
    }

    private static String optionLines(List<ReplyDto.OptionDto> options) {
        StringBuilder sb = new StringBuilder();
        for (ReplyDto.OptionDto o : options) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("  ").append(o.id()).append(". ").append(o.label());
            if (o.description() != null && !o.description().isBlank()) {
                sb.append(" — ").append(o.description());
            }
        }
        return sb.toString();
    }

    // ── 측정 도우미 ──────────────────────────────────────────────────────

    private List<ReplyDto.MeasureDto> measureDtos(RetrospectState state, ScriptStep step) {
        List<ReplyDto.MeasureDto> out = new ArrayList<>();
        for (MeasureField field : effectiveMeasures(state, step)) {
            out.add(ReplyDto.MeasureDto.of(field.key(), measureLabel(state, step, field)));
        }
        return out;
    }

    /** 이 회고에서 실제로 물을 측정 필드 — 일정이 없으면 일정 감정 슬라이더를 뺀다. */
    private static List<MeasureField> effectiveMeasures(RetrospectState state, ScriptStep step) {
        if (state.hasSchedule()) {
            return step.measures();
        }
        return step.measures().stream()
                .filter(f -> f != MeasureField.SCHEDULE_EMOTION)
                .toList();
    }

    private String measureLabel(RetrospectState state, ScriptStep step, MeasureField field) {
        return Scripts.measureLabel(field, state.schedule(), state.scheduleEmotion(),
                state.currentEmotion(), state.automaticThought(), "belief_after".equals(step.id()));
    }

    private String fill(RetrospectState state, String template) {
        return Scripts.fill(template, state.schedule(), state.scheduleEmotion(),
                state.currentEmotion(), state.automaticThought());
    }

    // ── 안전 ─────────────────────────────────────────────────────────────

    private void mergeSafety(RetrospectState state, SafetyLevel level, List<String> flags,
            String messageId) {
        boolean escalated = state.mergeSafety(level, flags, messageId);
        if (escalated) {
            events.publishEvent(new CrisisDetected(state.id(), state.safety().level(),
                    state.safety().flags(), messageId));
        }
    }

    /** 회고를 중단하고 고정 안내를 내보낸다. AI 는 부르지 않는다. */
    private ReplyDto safetyReply(RetrospectState state) {
        SafetyLevel level = state.safety().level();
        Guidance guidance = safetyPolicy.guidanceFor(level);
        String text = guidance != null ? guidance.render() : "잠시 멈추고 안전을 먼저 챙겨요.";

        state.addAssistantMessage(text);
        state.changePhase(Phase.ENDED);
        return ReplyDto.ended(text, level);
    }
}
