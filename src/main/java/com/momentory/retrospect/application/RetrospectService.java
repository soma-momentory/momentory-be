package com.momentory.retrospect.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.momentory.actioncard.application.ActionCardQueryService;
import com.momentory.common.time.DayBoundary;
import com.momentory.diary.application.DiaryQueryService;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.ExtractedEmotion;
import com.momentory.retrospect.domain.ExtractedEvent;
import com.momentory.retrospect.domain.Need;
import com.momentory.retrospect.domain.Phase;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.RetrospectStatus;
import com.momentory.retrospect.domain.TopicType;
import com.momentory.retrospect.domain.WishSentiment;
import com.momentory.retrospect.infrastructure.persistence.Retrospect;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.retrospect.infrastructure.persistence.RetrospectStateCodec;
import com.momentory.schedule.infrastructure.ScheduleRepository;
import com.momentory.user.application.AuthenticatedUserNotFoundException;
import com.momentory.user.domain.RestMethod;
import com.momentory.user.domain.UserProfile;
import com.momentory.user.infrastructure.UserProfileRepository;
import com.momentory.user.infrastructure.UserRepository;

/**
 * 회고 유스케이스 조율 — 인증 사용자·트랜잭션·영속화를 맡고, 실제 대화 흐름은 무상태
 * {@link RetrospectEngine} 에 위임한다.
 *
 * <p>세션 상태는 매 요청마다 {@code state_json} 에서 꺼내({@link RetrospectStateCodec})
 * 엔진을 돌리고 다시 저장한다. 사용자 소유권은 조회 단계에서 검증한다.
 */
@Service
public class RetrospectService {

    private final RetrospectEngine engine;
    private final RetrospectRepository retrospectRepository;
    private final DiaryQueryService diaryQueryService;
    private final ActionCardQueryService actionCardQueryService;
    private final PriorActionCardRecommender priorActionCardRecommender;
    private final RetrospectStateCodec codec;
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final ScheduleRepository scheduleRepository;
    private final ApplicationEventPublisher eventPublisher;

    public RetrospectService(RetrospectEngine engine, RetrospectRepository retrospectRepository,
            DiaryQueryService diaryQueryService, ActionCardQueryService actionCardQueryService,
            PriorActionCardRecommender priorActionCardRecommender,
            RetrospectStateCodec codec, UserRepository userRepository,
            UserProfileRepository userProfileRepository, ScheduleRepository scheduleRepository,
            ApplicationEventPublisher eventPublisher) {
        this.engine = engine;
        this.retrospectRepository = retrospectRepository;
        this.diaryQueryService = diaryQueryService;
        this.actionCardQueryService = actionCardQueryService;
        this.priorActionCardRecommender = priorActionCardRecommender;
        this.codec = codec;
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.scheduleRepository = scheduleRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 회고 시작 — 세션을 만들고 첫 메시지(공감 + 1턴 질문)를 낸다. 회고는 하루 한 번이라, 오늘(KST)
     * 이미 완주한 일기가 있으면 시작을 막는다({@link AlreadyRetrospectedTodayException}).
     */
    @Transactional
    public RetrospectResult start(Long userId, StartCommand command) {
        requireUser(userId);
        requireNoDiaryToday(userId);

        RetrospectState state = new RetrospectState(UUID.randomUUID().toString());
        ReplyDto reply = engine.start(state, command, preferredRestMethods(userId));

        Retrospect entity = Retrospect.start(userId, RetrospectStatus.from(state.phase()),
                resolveScheduleId(userId, state.scheduleId()), codec.serialize(state));
        retrospectRepository.save(entity);

        return new RetrospectResult(entity.getId(), reply);
    }

    /**
     * 회고 대상 일정 id 를 <b>이 사용자의 것인지</b> 확인해 그대로 쓰거나 버린다. FE 가 목록 밖
     * 자유 입력(id 없음)이나 오래된·남의 id 를 실어 보내도 FK 위반으로 저장이 터지지 않도록, 검증에
     * 실패하면 null 로 둔다(일정 이름·감정은 이미 state_json 에 남아 회고 진행에는 영향이 없다).
     */
    private Long resolveScheduleId(Long userId, Long scheduleId) {
        if (scheduleId == null) {
            return null;
        }
        return scheduleRepository.findByIdAndUserId(scheduleId, userId)
                .map(s -> scheduleId)
                .orElse(null);
    }

    /** 한 턴 진행 — 세션을 불러 엔진을 돌리고 갱신된 상태를 저장한다. */
    @Transactional
    public ReplyDto handle(Long userId, Long id, TurnCommand command) {
        Retrospect entity = requireSession(userId, id);

        RetrospectState state = codec.deserialize(entity.getStateJson());
        // 행동 추천 스텝에서만(지연) 부른다 — actioncard 컨텍스트가 비슷한 상황 이전 카드를 찾는다.
        state.priorCardFinder(situation -> priorActionCardRecommender.findSimilar(userId, situation));
        ReplyDto reply = engine.handle(state, command);
        sync(entity, state, reply);

        // 완료 턴이면 방금 저장된 일기·행동 카드의 id 를 응답에 실어 보낸다 — sync 가 발행한 이벤트가
        // 같은 트랜잭션에서 둘을 이미 저장했으므로 여기서 id 를 찾을 수 있다. 클라이언트는 다음 조회를
        // 기다리지 않고 이 id 로 곧바로 삭제·완료 반영을 할 수 있다.
        return withSavedIds(entity.getId(), reply);
    }

    /**
     * 응답의 일기·행동 카드에 저장된 서버 id 를 채운다 — 완료 턴(각 산출물이 있을 때)에서만 조회한다.
     * 저장이 아직 안 됐으면(있을 수 없지만 방어적으로) id 없이 그대로 보낸다.
     */
    private ReplyDto withSavedIds(Long retrospectId, ReplyDto reply) {
        ReplyDto enriched = reply;
        if (enriched.diary() != null) {
            enriched = enriched.withDiaryId(
                    diaryQueryService.findDiaryIdByRetrospect(retrospectId).orElse(null));
        }
        if (enriched.wishCard() != null) {
            enriched = enriched.withWishCardId(
                    actionCardQueryService.findIdByRetrospect(retrospectId).orElse(null));
        }
        return enriched;
    }

    private void sync(Retrospect entity, RetrospectState state, ReplyDto reply) {
        Phase phase = state.phase();
        Instant completedAt = phase.isTerminal() ? Instant.now() : null;
        entity.sync(RetrospectStatus.from(phase), codec.serialize(state), completedAt);
        announceCompletion(entity, state, reply);
    }

    /**
     * 완료 턴이 만든 산출물(일기·행동 카드)을 {@link RetrospectCompleted} 로 알린다 — 각 저장은
     * 이벤트를 구독하는 diary·actioncard 컨텍스트가 <b>같은 트랜잭션에서 동기로</b> 맡는다(retrospect
     * 는 그것들을 어떻게 저장하는지 모른다). 만들어진 산출물이 없으면(중도 턴·안전 중단) 발행하지 않는다.
     */
    private void announceCompletion(Retrospect entity, RetrospectState state, ReplyDto reply) {
        RetrospectCompleted.DiaryData diary = reply.diary() == null ? null
                : new RetrospectCompleted.DiaryData(primaryEmotion(state),
                        reply.diary().diary(), emotionTags(state));
        RetrospectCompleted.WishCardData card = reply.wishCard() == null ? null
                : new RetrospectCompleted.WishCardData(reply.wishCard().situation(),
                        state.confirmedEmotions(),
                        state.needs().stream().map(Need::word).toList(),
                        state.desiredState(), state.smallAction(),
                        WishSentiment.of(state.confirmedEmotions()).key());
        // 토픽은 일기가 나온 턴에만 만든다 — announceCompletion 은 매 턴 호출되므로, 매 턴 만들면
        // 대화 초반의 설익은 사건이 먼저 저장되고 완료 시점의 제대로 된 것이 존재 가드에 막힌다.
        List<RetrospectCompleted.TopicData> topics = diary == null ? List.of() : topicsFrom(state);
        if (diary == null && card == null && topics.isEmpty()) {
            return;
        }
        eventPublisher.publishEvent(
                new RetrospectCompleted(entity.getId(), entity.getUserId(), diary, card, topics));
    }

    /**
     * 완료 이벤트에 실을 토픽(주 일정·키워드 + 매칭 감정) — <b>추출 결과(G1)에서 파생시킨다.</b>
     *
     * <p>예전에는 토픽 전용 LLM 콜(G5)이 같은 대화를 다시 읽고 감정을 <b>두 번째로</b> 판단했다.
     * 두 판단이 갈리면 어느 쪽이 정답인지 채점할 수 없어(모델 비교 계획 §2.3-1) 감정 판단을 G1 하나로
     * 모았다. 여기서는 LLM 을 부르지 않는다 — 사건·키워드에 일정 id 를 잇고 감정을 물려줄 뿐이다.
     *
     * <p>사건 토픽의 이름은 {@link ExtractedEvent#topicLabel()}(짧은 label, 없으면 요약)이다.
     *
     * <p>예전에는 사건과 별개로 '키워드' 토픽({@link TopicType#KEYWORD})도 만들었는데, 모델이 사건
     * 이름을 그대로 재사용해 같은 주제가 두 행으로 쌓였다(관측: "가족 예배"·"개발"이 SCHEDULE 과
     * KEYWORD 로 각각 저장). 사건 label 이 이미 같은 역할을 하므로 키워드 생산을 없앴다.
     * {@code KEYWORD} 상수는 <b>남긴다</b> — 이미 저장된 행을 읽어야 한다.
     */
    static List<RetrospectCompleted.TopicData> topicsFrom(RetrospectState state) {
        List<ExtractedEvent> events = state.events();
        if (events.isEmpty()) {
            return List.of();
        }
        List<RetrospectCompleted.TopicData> topics = new ArrayList<>();
        for (ExtractedEvent e : events) {
            String label = e.topicLabel();
            if (label == null) {
                continue;
            }
            topics.add(new RetrospectCompleted.TopicData(TopicType.SCHEDULE,
                    linkedScheduleId(state, label), label, emotionsOfEvent(state, e.id())));
        }
        return topics;
    }

    /**
     * 그 사건에 붙은 감정. <b>사건이 하나뿐이면 사건에 못 붙은 감정도 그 사건 것으로 본다</b> —
     * 사건이 하나인 세션에서 감정이 어디에도 안 붙으면 토픽이 감정 없이 저장되기 때문이다.
     * 사건이 둘이면 어느 쪽인지 모르므로 붙이지 않는다(잘못 귀속시키느니 비운다).
     */
    private static List<Emotion> emotionsOfEvent(RetrospectState state, int eventId) {
        boolean single = state.events().size() == 1;
        LinkedHashSet<Emotion> emotions = new LinkedHashSet<>();
        for (ExtractedEmotion e : state.emotions()) {
            if (e.normalized() == null) {
                continue;
            }
            boolean mine = e.eventId() != null && e.eventId() == eventId;
            if (mine || (single && e.eventId() == null)) {
                emotions.add(e.normalized());
            }
        }
        return List.copyOf(emotions);
    }

    /** 뽑힌 사건 이름이 시작 시 고른 일정과 겹치면 그 일정 id 로 잇는다. 아니면 null. */
    private static Long linkedScheduleId(RetrospectState state, String label) {
        if (state.scheduleId() == null || state.schedule() == null) {
            return null;
        }
        String picked = state.schedule().strip();
        String name = label.strip();
        return name.contains(picked) || picked.contains(name) ? state.scheduleId() : null;
    }

    /**
     * 일기에 실을 대표 감정 — 확인된 감정 → 추출 감정 → 추론 감정 순.
     * v2 다중 감정 태그가 자리 잡기 전까지 기존 단일 컬럼 계약을 채우는 임시 매핑이다.
     */
    private static Emotion primaryEmotion(RetrospectState state) {
        List<Emotion> tags = emotionTags(state);
        return tags.isEmpty() ? null : tags.get(0);
    }

    /**
     * v2 일기 감정 태그 — 확인된 감정 + 추출된 정규화 감정(중복 제거, 확인된 것 우선).
     *
     * <p>둘 다 없으면 <b>추론 감정</b>을 쓴다. 사실만 말한 대화에서 사용자가 감정 탐색까지 건너뛰면
     * 화면에 후보를 보여줄 기회조차 없어 일기가 감정 없이 남는다 — 달력의 점도, 리포트의 셈도 빈다.
     *
     * <p>추론값이 <b>추출 기록({@code state.emotions})에는 들어가지 않는다</b>는 원칙은 그대로다.
     * 모델 비교의 채점기는 추출 기록만 보고, 일기의 Unsupported emotion rate 는 일기 <b>본문</b>을
     * 사람이 재므로 이 태그는 어느 쪽에도 닿지 않는다.
     */
    static List<Emotion> emotionTags(RetrospectState state) {
        LinkedHashSet<Emotion> tags = new LinkedHashSet<>(state.confirmedEmotions());
        for (var e : state.emotions()) {
            if (e.normalized() != null) {
                tags.add(e.normalized());
            }
        }
        if (tags.isEmpty() && state.inferredEmotion() != null) {
            tags.add(state.inferredEmotion());
        }
        return List.copyOf(tags);
    }

    private Retrospect requireSession(Long userId, Long id) {
        return retrospectRepository.findByIdAndUserId(id, userId)
                .orElseThrow(RetrospectSessionNotFoundException::new);
    }

    private void requireUser(Long userId) {
        userRepository.findById(userId)
                .orElseThrow(AuthenticatedUserNotFoundException::new);
    }

    /**
     * 오늘(KST · 04:00 하루 경계) 이미 완주한 일기가 있으면 새 회고 시작을 막는다 — 회고는 하루
     * 한 번이다. 하루가 자정이 아니라 새벽 4시에 넘어가므로({@link DayBoundary}), 밤 11시와 다음날
     * 새벽 2시는 같은 「하루」다. 일기는 회고 완료 때만 생기므로, 중도 이탈(일기 미저장)이면 오늘
     * 안에 다시 시작할 수 있다.
     */
    private void requireNoDiaryToday(Long userId) {
        if (diaryQueryService.hasDiaryOn(userId, DayBoundary.today())) {
            throw new AlreadyRetrospectedTodayException();
        }
    }

    /**
     * 온보딩에서 고른 '평소 선호하는 쉬는 방법'을 프롬프트에 실을 한글 라벨 목록으로 뽑는다.
     * 프로필·선호가 없으면 빈 목록(→ 프롬프트에 선호 블록이 안 붙어 기존 동작 그대로).
     *
     * <p>"기타"는 사용자가 적은 상세 문구로 바꾸고(비었으면 버림), "그때마다 달라요"는 특정 행동을
     * 가리키지 않아 제외한다. 열거형 정의 순서로 정렬해 매 요청 같은 순서로 실린다(집합은 순서 불정).
     */
    private List<String> preferredRestMethods(Long userId) {
        return userProfileRepository.findById(userId)
                .map(RetrospectService::resolveRestMethods)
                .orElseGet(List::of);
    }

    private static List<String> resolveRestMethods(UserProfile profile) {
        return profile.getRestMethods().stream()
                .filter(m -> m != RestMethod.VARIES_BY_DAY)
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .map(m -> restMethodLabel(m, profile.getOtherRestMethodDetail()))
                .filter(label -> label != null && !label.isBlank())
                .toList();
    }

    private static String restMethodLabel(RestMethod method, String otherDetail) {
        return method == RestMethod.OTHER ? otherDetail : method.getLabel();
    }
}
