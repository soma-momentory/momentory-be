package com.momentory.retrospect.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.safety.SafetyLevel;
import com.momentory.retrospect.domain.script.OptionItem;
import com.momentory.retrospect.domain.script.RetroMode;
import com.momentory.retrospect.domain.script.ScriptStep;
import com.momentory.retrospect.domain.script.Scripts;

/**
 * 회고 세션 애그리거트 루트 (채팅 최적 시나리오 기준 전면 재설계).
 *
 * <p>세션 스코프 상태를 전부 소유한다 — 진입 선택(일정·감정 2종·닉네임), 진행 위치(phase·step),
 * 답변·측정 기록, 안전 상태, 대화 로그. 상태 변경은 이 클래스의 메서드로만 한다.
 */
public class RetrospectState {

    private final String id;

    // 진입 선택 (시작 후 불변)
    private String nickname;
    private String schedule;
    private Emotion scheduleEmotion;
    private Emotion currentEmotion;
    /** 온보딩 관심분야 — 일정 선택 규칙, 그리고 일정 없는 회고의 질문 구체화에 쓴다. */
    private String interest;
    /** 아직 대상 일정을 못 골랐을 때의 후보 목록 (await_schedule 에서만 사용). */
    private List<ScheduleItem> pendingSchedules = List.of();

    // 진행 위치
    private Phase phase = Phase.INTRO;
    private RetroMode mode;
    private List<ScriptStep> steps = List.of();
    /** 현재 '물어본' 스텝의 인덱스. 스크립트 진입 전엔 -1. */
    private int stepIndex = -1;
    private int turn;
    /** 현재 스텝(또는 intro)에서 되물은 횟수 — 게이트 캡용. 전진할 때마다 0으로. */
    private int reasks;

    // 기록
    private final Map<String, String> answers = new LinkedHashMap<>();
    /** stepId → (measureField key → 0~10 값) */
    private final Map<String, Map<String, Integer>> measures = new LinkedHashMap<>();
    private final List<Message> messages = new ArrayList<>();
    private final SafetyState safety = new SafetyState();

    /** 이해 확인 AI가 뽑은 한 줄 상황 요약 — 행동 카드의 '상황' 칸. */
    private String situationSummary;
    /** 직전에 내보낸 선택지 — optionId(1-base 번호 문자열) 해석용. */
    private List<OptionItem> lastOptions = List.of();
    /** 행동 선택 턴에서 고른 행동 — 행동 카드의 '목표 행동'. */
    private OptionItem chosenAction;

    private int messageSeq;

    public RetrospectState(String id) {
        this.id = id;
    }

    // ── 조회 ─────────────────────────────────────────────────────────────

    public String id() {
        return id;
    }

    public String nickname() {
        return nickname;
    }

    public String schedule() {
        return schedule;
    }

    public Emotion scheduleEmotion() {
        return scheduleEmotion;
    }

    public Emotion currentEmotion() {
        return currentEmotion;
    }

    public String interest() {
        return interest;
    }

    /** 특정 일정을 골라 진행 중인가. false면 '오늘 하루'를 현재 감정 하나로 돌아보는 회고다. */
    public boolean hasSchedule() {
        return schedule != null;
    }

    public Phase phase() {
        return phase;
    }

    public RetroMode mode() {
        return mode;
    }

    public int turn() {
        return turn;
    }

    public SafetyState safety() {
        return safety;
    }

    public List<Message> messages() {
        return List.copyOf(messages);
    }

    public Map<String, String> answers() {
        return Map.copyOf(answers);
    }

    public Map<String, Map<String, Integer>> measures() {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        measures.forEach((k, v) -> out.put(k, Map.copyOf(v)));
        return out;
    }

    public String situationSummary() {
        return situationSummary;
    }

    public List<OptionItem> lastOptions() {
        return List.copyOf(lastOptions);
    }

    public OptionItem chosenAction() {
        return chosenAction;
    }

    /** 인지 재구성형의 자동적 사고 (믿음 슬라이더 라벨·일기 프롬프트용). */
    public String automaticThought() {
        return answers.get("automatic_thought");
    }

    // ── 시작 ─────────────────────────────────────────────────────────────

    public void begin(String schedule, Emotion scheduleEmotion, Emotion currentEmotion,
            String nickname) {
        this.currentEmotion = currentEmotion;
        this.nickname = nickname;
        assignSchedule(new ScheduleItem(schedule, scheduleEmotion));
    }

    /** 특정 일정 없이 시작 — 현재 감정 하나로 '오늘 하루'를 돌아본다. */
    public void beginNoSchedule(Emotion currentEmotion, String nickname, String interest) {
        this.currentEmotion = currentEmotion;
        this.nickname = nickname;
        this.interest = interest;
        this.schedule = null;
        this.scheduleEmotion = null;
        this.phase = Phase.INTRO;
    }

    /** 일정을 규칙으로 못 골랐다 — 후보를 들고 선택을 기다린다. */
    public void beginPendingSchedules(List<ScheduleItem> schedules, Emotion currentEmotion,
            String nickname, String interest) {
        this.pendingSchedules = List.copyOf(schedules);
        this.currentEmotion = currentEmotion;
        this.nickname = nickname;
        this.interest = interest;
        this.phase = Phase.AWAIT_SCHEDULE;
    }

    public List<ScheduleItem> pendingSchedules() {
        return List.copyOf(pendingSchedules);
    }

    /** 대상 일정 확정 — 1턴 질문을 낼 준비가 됐다. */
    public void assignSchedule(ScheduleItem item) {
        this.schedule = item.name();
        this.scheduleEmotion = item.emotion();
        this.pendingSchedules = List.of();
        this.phase = Phase.INTRO;
    }

    // ── 진행 ─────────────────────────────────────────────────────────────

    public void changePhase(Phase phase) {
        this.phase = phase;
    }

    /** 방향이 확정됐다 — 모드 스크립트를 걸고 script phase 로 넘어간다. */
    public void applyMode(RetroMode mode) {
        this.mode = mode;
        this.steps = Scripts.stepsOf(mode);
        this.stepIndex = -1;
        this.phase = Phase.SCRIPT;
    }

    /** 지금 답을 기다리는 스텝(= 마지막으로 물어본 스텝). */
    public Optional<ScriptStep> currentStep() {
        return stepIndex >= 0 && stepIndex < steps.size()
                ? Optional.of(steps.get(stepIndex))
                : Optional.empty();
    }

    /** 다음 스텝으로 이동한다. 스크립트가 끝났으면 empty. */
    public Optional<ScriptStep> advanceStep() {
        stepIndex++;
        return currentStep();
    }

    /** 커밋 없이 다음 스텝만 엿본다 — 전진 여부를 판정한 뒤 확정하는 게이트용. */
    public Optional<ScriptStep> peekNext() {
        int i = stepIndex + 1;
        return i >= 0 && i < steps.size() ? Optional.of(steps.get(i)) : Optional.empty();
    }

    /** {@link #peekNext()}로 본 다음 스텝을 실제로 확정한다. */
    public void commitAdvance() {
        if (stepIndex + 1 < steps.size()) {
            stepIndex++;
        }
    }

    // ── 게이트(재질문 캡) ────────────────────────────────────────────────

    public int reasks() {
        return reasks;
    }

    public void bumpReask() {
        reasks++;
    }

    public void resetReask() {
        reasks = 0;
    }

    // ── 기록 ─────────────────────────────────────────────────────────────

    public void recordAnswer(String stepId, String value) {
        if (value != null && !value.isBlank()) {
            answers.put(stepId, value.strip());
        }
    }

    /** 슬라이더 값 기록. 0~10 범위를 벗어나면 잘라 넣는다. */
    public void recordMeasure(String stepId, String fieldKey, int value) {
        int clamped = Math.max(0, Math.min(10, value));
        measures.computeIfAbsent(stepId, k -> new LinkedHashMap<>()).put(fieldKey, clamped);
    }

    public void situationSummary(String summary) {
        if (summary != null && !summary.isBlank()) {
            this.situationSummary = summary.strip();
        }
    }

    public void lastOptions(List<OptionItem> options) {
        this.lastOptions = options == null ? List.of() : List.copyOf(options);
    }

    /** 1-base 번호 문자열(optionId)로 직전 선택지를 해석한다. */
    public Optional<OptionItem> resolveOption(String optionId) {
        try {
            int idx = Integer.parseInt(optionId.strip()) - 1;
            return idx >= 0 && idx < lastOptions.size()
                    ? Optional.of(lastOptions.get(idx))
                    : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public void chooseAction(OptionItem option) {
        this.chosenAction = option;
    }

    // ── 메시지 ───────────────────────────────────────────────────────────

    public Message addAssistantMessage(String content) {
        Message m = new Message("m" + (++messageSeq), Message.ROLE_ASSISTANT, content, null);
        messages.add(m);
        return m;
    }

    public Message addUserMessage(String content) {
        Message m = new Message("m" + (++messageSeq), Message.ROLE_USER, content, null);
        messages.add(m);
        turn++;
        return m;
    }

    // ── 안전 ─────────────────────────────────────────────────────────────

    /** @return 레벨이 올라갔으면 true (호출자가 도메인 이벤트를 낸다) */
    public boolean mergeSafety(SafetyLevel level, Collection<String> flags, String msgId) {
        return safety.merge(level, flags, msgId);
    }

    // ── 영속화 스냅샷 ────────────────────────────────────────────────────
    //
    // 세션 상태를 그대로 DB에 담기 위한 순수 스냅샷 변환. steps 는 mode 에서 파생되므로
    // 저장하지 않고 복원 시 다시 도출한다. 도메인은 프레임워크(Jackson 등)를 모른 채로 두고,
    // 직렬화는 인프라의 코덱이 이 순수 record 를 대상으로 한다.

    public RetrospectStateSnapshot toSnapshot() {
        Map<String, Map<String, Integer>> measuresCopy = new LinkedHashMap<>();
        measures.forEach((k, v) -> measuresCopy.put(k, new LinkedHashMap<>(v)));
        return new RetrospectStateSnapshot(
                id, nickname, schedule, scheduleEmotion, currentEmotion, interest,
                List.copyOf(pendingSchedules), phase, mode, stepIndex, turn, reasks,
                new LinkedHashMap<>(answers), measuresCopy, new ArrayList<>(messages),
                new RetrospectStateSnapshot.SafetySnapshot(
                        safety.level(), safety.flags(), safety.lastFlaggedMsgId()),
                situationSummary, List.copyOf(lastOptions), chosenAction, messageSeq);
    }

    public static RetrospectState fromSnapshot(RetrospectStateSnapshot s) {
        RetrospectState state = new RetrospectState(s.id());
        state.nickname = s.nickname();
        state.schedule = s.schedule();
        state.scheduleEmotion = s.scheduleEmotion();
        state.currentEmotion = s.currentEmotion();
        state.interest = s.interest();
        state.pendingSchedules = s.pendingSchedules() == null ? List.of()
                : List.copyOf(s.pendingSchedules());
        state.phase = s.phase();
        state.mode = s.mode();
        state.steps = s.mode() == null ? List.of() : Scripts.stepsOf(s.mode());
        state.stepIndex = s.stepIndex();
        state.turn = s.turn();
        state.reasks = s.reasks();
        if (s.answers() != null) {
            state.answers.putAll(s.answers());
        }
        if (s.measures() != null) {
            s.measures().forEach((k, v) -> state.measures.put(k, new LinkedHashMap<>(v)));
        }
        if (s.messages() != null) {
            state.messages.addAll(s.messages());
        }
        if (s.safety() != null) {
            // merge 는 단조 증가라 NONE→저장값 복원에 그대로 쓸 수 있다(레벨 상승·플래그 적재·msgId).
            state.safety.merge(s.safety().level(), s.safety().flags(),
                    s.safety().lastFlaggedMsgId());
        }
        state.situationSummary = s.situationSummary();
        state.lastOptions = s.lastOptions() == null ? List.of() : List.copyOf(s.lastOptions());
        state.chosenAction = s.chosenAction();
        state.messageSeq = s.messageSeq();
        return state;
    }
}
