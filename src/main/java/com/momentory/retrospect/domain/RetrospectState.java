package com.momentory.retrospect.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.momentory.retrospect.domain.safety.SafetyLevel;

/**
 * 회고 세션 애그리거트 루트 (채팅흐름_v2).
 *
 * <p>세션 스코프 상태를 전부 소유한다 — 진입 컨텍스트(일정·닉네임·관심사), 진행 위치(phase),
 * 일기 작성 슬롯(사건·감정·의미), 감정 탐색 슬롯(감정·바람·바랐던 모습·작은 행동), 대화 로그, 안전
 * 상태. 상태 변경은 이 클래스의 메서드로만 한다.
 *
 * <p>흐름: {@code diary_chat}(≤6턴, 사건·감정·의미 수집) → {@code await_branch}(감정 더 알아볼까)
 * → (선택 시) {@code emotion_exploration}(고정 3턴) → {@code complete}. 종료·다음 슬롯 선택은
 * 서버(엔진)가 이 슬롯 상태로 결정한다.
 */
public class RetrospectState {

    /** 일기 작성 최대 턴 (채팅흐름_v2). */
    public static final int DIARY_MAX_TURNS = 6;
    /** 한 세션에서 뽑는 사건 개수 상한 (모델 비교 계획 §3.1). */
    public static final int MAX_EVENTS = 2;
    /** 한 세션에서 뽑는 키워드 개수 상한 (모델 비교 계획 §3.4). */
    public static final int MAX_KEYWORDS = 2;
    /**
     * 일기 작성 권장 최소 턴 — 엔진은 6턴(최대)까지 대화를 이어가지만(조기 종료 금지), AI 에게는
     * 이 값을 하한으로 알려 "최소 이만큼은 소재를 넓혀가라"는 진행 안내에 쓴다({@code PromptFactory}).
     */
    public static final int DIARY_MIN_TURNS = 4;
    /** 감정 탐색 고정 턴 (감정 확인 → 바람 → 작은 행동). */
    public static final int EXPLORATION_MAX_TURNS = 3;

    private final String id;

    // 진입 컨텍스트 (시작 후 불변)
    private String nickname;
    /** 개인화 소재로 고른 일정의 schedules 테이블 id — 없으면(오늘 하루·자유 입력) null. */
    private Long scheduleId;
    /** 개인화 소재로 고른 일정 이름 — 없으면 null('오늘 하루' 회고). */
    private String schedule;
    /** 그 일정에 사용자가 홈에서 단 감정 — 대화 소재로만 쓴다(없으면 null). */
    private Emotion scheduleEmotion;
    private String interest;
    /** 온보딩 '평소 쉬는 방법' 라벨 — 작은 행동 제안에 선호를 반영한다(없으면 빈 목록). */
    private List<String> restMethods = List.of();

    // 진행 위치
    private Phase phase = Phase.DIARY_CHAT;
    /** 위기 안내로 멈췄을 때 「이어서 얘기하기」가 되돌아갈 phase. hold 중이 아니면 null. */
    private Phase heldFrom;
    /** 현재 턴에서 되물은 횟수 — 게이트 캡용. 전진할 때마다 0으로. */
    private int reasks;
    /** 연속 어뷰징 턴 수 — 캡 도달 시 부드럽게 종료. 정상 답변에 0으로. */
    private int abuseStreak;

    // 일기 작성 슬롯 (diary_chat)
    private int diaryTurn;
    /** 핵심(중심) 사건 — 감정 탐색·바람 카드가 참조하는 유일한 사건. */
    private String event;
    /** 곁가지로 언급된 사건 — 일기 본문에만 가볍게. */
    private final List<String> secondaryEvents = new ArrayList<>();
    /** 사건 — 대화 끝에 {@link com.momentory.retrospect.domain.assistant.EmotionExtractor} 가 감정과 함께 채운다(≤2). */
    private final List<ExtractedEvent> events = new ArrayList<>();
    /** 감정 — 대화 끝에 {@link com.momentory.retrospect.domain.assistant.EmotionExtractor} 가 한 번에 채운다. */
    private final List<ExtractedEmotion> emotions = new ArrayList<>();
    /** 키워드 — 사건과 같은 추출 콜에서 함께 나온다(≤2). 토픽 누적·집계의 단위. */
    private final List<ExtractedKeyword> keywords = new ArrayList<>();
    /** 감정 표현이 대화에 한 번이라도 담겼는가 — 이른 종료 판정의 감정 신호. */
    private boolean emotionSeen;
    /** 무엇이 마음에 남았는가. */
    private String meaning;
    /** 생성된 일기 초안 — 채팅 중에는 노출하지 않는다. */
    private String diaryDraft;
    /** 사용자가 「그만할래」로 일기 작성을 끝냈는가. */
    private boolean diaryUserEnded;

    // 감정 탐색 슬롯 (emotion_exploration)
    private boolean explorationEntered;
    private int explorationTurn;
    private final List<Emotion> confirmedEmotions = new ArrayList<>();
    private final List<Need> needs = new ArrayList<>();
    private String desiredState;
    private String smallAction;

    // 기록
    private final List<Message> messages = new ArrayList<>();
    private final SafetyState safety = new SafetyState();
    private int messageSeq;

    /** 직전에 내보낸 선택지 — optionId(1-base 번호 문자열) 해석용. */
    private List<Choice> lastOptions = List.of();

    /**
     * "비슷한 상황의 이전 바람 카드" 조회기 — <b>직렬화하지 않는다</b>. 서비스가 매 턴 세션을 불러온 뒤
     * userId 를 묶어 넣어준다. 엔진은 작은 행동을 제안할 때만 부른다.
     */
    private transient PriorActionCardFinder priorCardFinder = PriorActionCardFinder.NONE;

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

    public Long scheduleId() {
        return scheduleId;
    }

    public String schedule() {
        return schedule;
    }

    public Emotion scheduleEmotion() {
        return scheduleEmotion;
    }

    public String interest() {
        return interest;
    }

    public List<String> restMethods() {
        return List.copyOf(restMethods);
    }

    public void restMethods(List<String> restMethods) {
        this.restMethods = restMethods == null ? List.of() : List.copyOf(restMethods);
    }

    /** 개인화 소재로 고른 일정이 있는가. false면 '오늘 하루'를 돌아보는 회고다. */
    public boolean hasSchedule() {
        return schedule != null;
    }

    public Phase phase() {
        return phase;
    }

    public SafetyState safety() {
        return safety;
    }

    public List<Message> messages() {
        return List.copyOf(messages);
    }

    public PriorActionCardFinder priorCardFinder() {
        return priorCardFinder;
    }

    // ── 시작 ─────────────────────────────────────────────────────────────

    /**
     * 회고를 연다 — 개인화 소재({@code schedule}, 없으면 null)와 프로필을 심고 일기 작성 채팅에서
     * 시작한다. v2 는 시작 시 감정을 고르지 않는다.
     */
    public void begin(String schedule, Emotion scheduleEmotion, Long scheduleId, String nickname,
            String interest) {
        this.schedule = schedule;
        this.scheduleEmotion = scheduleEmotion;
        this.scheduleId = scheduleId;
        this.nickname = nickname;
        this.interest = interest;
        this.phase = Phase.DIARY_CHAT;
    }

    // ── 진행 ─────────────────────────────────────────────────────────────

    public void changePhase(Phase phase) {
        this.phase = phase;
    }

    /** 위기 안내로 멈춘다 — 지금 phase 를 기억해 두고 {@link Phase#SAFETY_HOLD} 로 넘어간다. */
    public void holdForSafety() {
        this.heldFrom = this.phase;
        this.phase = Phase.SAFETY_HOLD;
    }

    /** 「이어서 얘기하기」 — 멈추기 전 phase 로 되돌리고 정지 신호를 거둔다. 되돌아간 phase 를 준다. */
    public Phase resumeFromHold() {
        Phase target = heldFrom != null ? heldFrom : Phase.DIARY_CHAT;
        this.phase = target;
        this.heldFrom = null;
        this.safety.reset();
        return target;
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

    public int abuseStreak() {
        return abuseStreak;
    }

    public void bumpAbuse() {
        abuseStreak++;
    }

    public void resetAbuse() {
        abuseStreak = 0;
    }

    // ── 일기 작성 슬롯 ───────────────────────────────────────────────────

    public int diaryTurn() {
        return diaryTurn;
    }

    public void bumpDiaryTurn() {
        diaryTurn++;
    }

    public String event() {
        return event;
    }

    public void event(String event) {
        if (event != null && !event.isBlank()) {
            this.event = event.strip();
        }
    }

    public List<String> secondaryEvents() {
        return List.copyOf(secondaryEvents);
    }

    public void addSecondaryEvents(Collection<String> events) {
        if (events == null) {
            return;
        }
        for (String e : events) {
            if (e != null && !e.isBlank() && !secondaryEvents.contains(e.strip())) {
                secondaryEvents.add(e.strip());
            }
        }
    }

    public List<ExtractedEvent> events() {
        return List.copyOf(events);
    }

    /** 대화 끝에 추출한 사건으로 채운다 — 요약이 있는 것만, 최대 2개. */
    public void events(List<ExtractedEvent> extracted) {
        events.clear();
        if (extracted == null) {
            return;
        }
        for (ExtractedEvent e : extracted) {
            if (e != null && e.summary() != null && events.size() < MAX_EVENTS) {
                events.add(e);
            }
        }
    }

    public List<ExtractedKeyword> keywords() {
        return List.copyOf(keywords);
    }

    /** 대화 끝에 추출한 키워드로 채운다 — 라벨이 있는 것만, 최대 2개. */
    public void keywords(List<ExtractedKeyword> extracted) {
        keywords.clear();
        if (extracted == null) {
            return;
        }
        for (ExtractedKeyword k : extracted) {
            if (k != null && k.label() != null && keywords.size() < MAX_KEYWORDS) {
                keywords.add(k);
            }
        }
    }

    public List<ExtractedEmotion> emotions() {
        return List.copyOf(emotions);
    }

    /** 대화 끝에 추출한 감정으로 채운다(정규화된 것만 남긴다). */
    public void emotions(List<ExtractedEmotion> extracted) {
        emotions.clear();
        if (extracted != null) {
            for (ExtractedEmotion e : extracted) {
                if (e != null) {
                    emotions.add(e);
                }
            }
        }
    }

    public boolean emotionSeen() {
        return emotionSeen;
    }

    public void markEmotionSeen() {
        this.emotionSeen = true;
    }

    public String meaning() {
        return meaning;
    }

    public void meaning(String meaning) {
        if (meaning != null && !meaning.isBlank()) {
            this.meaning = meaning.strip();
        }
    }

    public String diaryDraft() {
        return diaryDraft;
    }

    public void diaryDraft(String diaryDraft) {
        this.diaryDraft = diaryDraft == null ? null : diaryDraft.strip();
    }

    public boolean diaryUserEnded() {
        return diaryUserEnded;
    }

    public void markDiaryUserEnded() {
        this.diaryUserEnded = true;
    }

    /** 세 정보(사건·감정·의미)가 다 모였는가 — 이른 종료 판정. */
    public boolean diarySlotsComplete() {
        return event != null && emotionSeen && meaning != null;
    }

    /** 일기 작성 턴을 다 썼는가. */
    public boolean diaryTurnsExhausted() {
        return diaryTurn >= DIARY_MAX_TURNS;
    }

    // ── 감정 탐색 슬롯 ───────────────────────────────────────────────────

    public boolean explorationEntered() {
        return explorationEntered;
    }

    public void enterExploration() {
        this.explorationEntered = true;
        this.explorationTurn = 0;
        this.phase = Phase.EMOTION_EXPLORATION;
    }

    public int explorationTurn() {
        return explorationTurn;
    }

    public void bumpExplorationTurn() {
        explorationTurn++;
    }

    public List<Emotion> confirmedEmotions() {
        return List.copyOf(confirmedEmotions);
    }

    public void confirmEmotions(Collection<Emotion> chosen) {
        confirmedEmotions.clear();
        if (chosen != null) {
            for (Emotion e : chosen) {
                if (e != null && !confirmedEmotions.contains(e)) {
                    confirmedEmotions.add(e);
                }
            }
        }
    }

    public List<Need> needs() {
        return List.copyOf(needs);
    }

    public void chooseNeeds(Collection<Need> chosen) {
        needs.clear();
        if (chosen != null) {
            for (Need n : chosen) {
                if (n != null && !needs.contains(n)) {
                    needs.add(n);
                }
            }
        }
    }

    public String desiredState() {
        return desiredState;
    }

    public void desiredState(String desiredState) {
        if (desiredState != null && !desiredState.isBlank()) {
            this.desiredState = desiredState.strip();
        }
    }

    public String smallAction() {
        return smallAction;
    }

    public void smallAction(String smallAction) {
        if (smallAction != null && !smallAction.isBlank()) {
            this.smallAction = smallAction.strip();
        }
    }

    // ── 선택지 ───────────────────────────────────────────────────────────

    public void lastOptions(List<Choice> options) {
        this.lastOptions = options == null ? List.of() : List.copyOf(options);
    }

    public List<Choice> lastOptions() {
        return List.copyOf(lastOptions);
    }

    /** 1-base 번호 문자열(optionId)로 직전 선택지를 해석한다. */
    public Optional<Choice> resolveChoice(String optionId) {
        if (optionId == null) {
            return Optional.empty();
        }
        try {
            int idx = Integer.parseInt(optionId.strip()) - 1;
            return idx >= 0 && idx < lastOptions.size()
                    ? Optional.of(lastOptions.get(idx))
                    : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public void priorCardFinder(PriorActionCardFinder finder) {
        this.priorCardFinder = finder == null ? PriorActionCardFinder.NONE : finder;
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
        return m;
    }

    // ── 안전 ─────────────────────────────────────────────────────────────

    /** @return 레벨이 올라갔으면 true (호출자가 도메인 이벤트를 낸다) */
    public boolean mergeSafety(SafetyLevel level, Collection<String> flags, String msgId) {
        return safety.merge(level, flags, msgId);
    }

    // ── 영속화 스냅샷 ────────────────────────────────────────────────────

    public RetrospectStateSnapshot toSnapshot() {
        return new RetrospectStateSnapshot(
                id, nickname, scheduleId, schedule, scheduleEmotion, interest,
                List.copyOf(restMethods), phase, heldFrom, reasks, abuseStreak,
                diaryTurn, event, List.copyOf(secondaryEvents), List.copyOf(events),
                List.copyOf(emotions), List.copyOf(keywords), emotionSeen,
                meaning, diaryDraft, diaryUserEnded,
                explorationEntered, explorationTurn, List.copyOf(confirmedEmotions),
                List.copyOf(needs), desiredState, smallAction,
                new ArrayList<>(messages),
                new RetrospectStateSnapshot.SafetySnapshot(
                        safety.level(), safety.flags(), safety.lastFlaggedMsgId()),
                List.copyOf(lastOptions), messageSeq);
    }

    public static RetrospectState fromSnapshot(RetrospectStateSnapshot s) {
        RetrospectState state = new RetrospectState(s.id());
        state.nickname = s.nickname();
        state.scheduleId = s.scheduleId();
        state.schedule = s.schedule();
        state.scheduleEmotion = s.scheduleEmotion();
        state.interest = s.interest();
        state.restMethods = s.restMethods() == null ? List.of() : List.copyOf(s.restMethods());
        state.phase = s.phase();
        state.heldFrom = s.heldFrom();
        state.reasks = s.reasks();
        state.abuseStreak = s.abuseStreak();
        state.diaryTurn = s.diaryTurn();
        state.event = s.event();
        if (s.secondaryEvents() != null) {
            state.secondaryEvents.addAll(s.secondaryEvents());
        }
        if (s.events() != null) {
            state.events.addAll(s.events());
        }
        if (s.emotions() != null) {
            state.emotions.addAll(s.emotions());
        }
        if (s.keywords() != null) {
            state.keywords.addAll(s.keywords());
        }
        state.emotionSeen = s.emotionSeen();
        state.meaning = s.meaning();
        state.diaryDraft = s.diaryDraft();
        state.diaryUserEnded = s.diaryUserEnded();
        state.explorationEntered = s.explorationEntered();
        state.explorationTurn = s.explorationTurn();
        if (s.confirmedEmotions() != null) {
            state.confirmedEmotions.addAll(s.confirmedEmotions());
        }
        if (s.needs() != null) {
            state.needs.addAll(s.needs());
        }
        state.desiredState = s.desiredState();
        state.smallAction = s.smallAction();
        if (s.messages() != null) {
            state.messages.addAll(s.messages());
        }
        if (s.safety() != null) {
            state.safety.merge(s.safety().level(), s.safety().flags(),
                    s.safety().lastFlaggedMsgId());
        }
        state.lastOptions = s.lastOptions() == null ? List.of() : List.copyOf(s.lastOptions());
        state.messageSeq = s.messageSeq();
        return state;
    }
}
