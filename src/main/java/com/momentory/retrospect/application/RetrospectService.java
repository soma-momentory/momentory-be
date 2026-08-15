package com.momentory.retrospect.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.momentory.common.time.TimeZonePolicy;
import com.momentory.diary.application.DiaryQueryService;
import com.momentory.retrospect.domain.Phase;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.RetrospectStatus;
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

    /** "하루 한 번" 가드의 하루 경계 기준 — 일기 월별 조회와 같은 KST. */
    private static final ZoneId ZONE = TimeZonePolicy.DEFAULT_ZONE_ID;

    private final RetrospectEngine engine;
    private final RetrospectRepository retrospectRepository;
    private final DiaryQueryService diaryQueryService;
    private final PriorActionCardRecommender priorActionCardRecommender;
    private final RetrospectStateCodec codec;
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final ScheduleRepository scheduleRepository;
    private final ApplicationEventPublisher eventPublisher;

    public RetrospectService(RetrospectEngine engine, RetrospectRepository retrospectRepository,
            DiaryQueryService diaryQueryService, PriorActionCardRecommender priorActionCardRecommender,
            RetrospectStateCodec codec, UserRepository userRepository,
            UserProfileRepository userProfileRepository, ScheduleRepository scheduleRepository,
            ApplicationEventPublisher eventPublisher) {
        this.engine = engine;
        this.retrospectRepository = retrospectRepository;
        this.diaryQueryService = diaryQueryService;
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
                state.mode(), resolveScheduleId(userId, state.scheduleId()),
                state.currentEmotion(), codec.serialize(state));
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

        return reply;
    }

    private void sync(Retrospect entity, RetrospectState state, ReplyDto reply) {
        Phase phase = state.phase();
        Instant completedAt = phase.isTerminal() ? Instant.now() : null;
        entity.sync(RetrospectStatus.from(phase), state.mode(), codec.serialize(state),
                completedAt);
        announceCompletion(entity, state, reply);
    }

    /**
     * 완료 턴이 만든 산출물(일기·행동 카드)을 {@link RetrospectCompleted} 로 알린다 — 각 저장은
     * 이벤트를 구독하는 diary·actioncard 컨텍스트가 <b>같은 트랜잭션에서 동기로</b> 맡는다(retrospect
     * 는 그것들을 어떻게 저장하는지 모른다). 만들어진 산출물이 없으면(중도 턴·안전 중단) 발행하지 않는다.
     */
    private void announceCompletion(Retrospect entity, RetrospectState state, ReplyDto reply) {
        RetrospectCompleted.DiaryData diary = reply.diary() == null ? null
                : new RetrospectCompleted.DiaryData(state.currentEmotion(), state.scheduleEmotion(),
                        reply.diary().diary(), reply.diary().reframedDiary());
        RetrospectCompleted.ActionCardData card = reply.actionCard() == null ? null
                : new RetrospectCompleted.ActionCardData(reply.actionCard().situation(),
                        reply.actionCard().action(), fromRestPreference(state));
        if (diary == null && card == null) {
            return;
        }
        eventPublisher.publishEvent(
                new RetrospectCompleted(entity.getId(), entity.getUserId(), diary, card));
    }

    /** 사용자가 최종 선택한 행동이 '쉬는 방법 선호'로 만든 카드였는지 — 분석용 내부 표식(비노출). */
    private boolean fromRestPreference(RetrospectState state) {
        return state.chosenAction() != null && state.chosenAction().restPreference();
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
     * 오늘(KST) 이미 완주한 일기가 있으면 새 회고 시작을 막는다 — 회고는 하루 한 번이다. 일기는
     * 회고 완료 때만 생기므로, 중도 이탈(일기 미저장)이면 오늘 안에 다시 시작할 수 있다.
     */
    private void requireNoDiaryToday(Long userId) {
        if (diaryQueryService.hasDiaryOn(userId, LocalDate.now(ZONE))) {
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
