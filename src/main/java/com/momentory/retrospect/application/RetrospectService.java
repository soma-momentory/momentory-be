package com.momentory.retrospect.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

import com.momentory.common.time.TimeZonePolicy;
import com.momentory.retrospect.domain.Phase;
import com.momentory.retrospect.domain.PriorActionCard;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.RetrospectStatus;
import com.momentory.retrospect.domain.assistant.SituationEmbedder;
import com.momentory.retrospect.infrastructure.persistence.ActionCard;
import com.momentory.retrospect.infrastructure.persistence.ActionCardRepository;
import com.momentory.retrospect.infrastructure.persistence.Diary;
import com.momentory.retrospect.infrastructure.persistence.DiaryRepository;
import com.momentory.retrospect.infrastructure.persistence.Retrospect;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.retrospect.infrastructure.persistence.RetrospectStateCodec;
import com.momentory.retrospect.infrastructure.persistence.VectorLiteral;
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

    private static final Logger log = LoggerFactory.getLogger(RetrospectService.class);

    /**
     * 코사인 거리 임계값 — 이 미만이어야 "비슷한 상황"으로 본다(0=동일, 2=정반대).
     * 너무 크면 관계없는 카드가 추천되고, 너무 작으면 거의 안 걸린다. 튜닝 지점.
     */
    private static final double SIMILAR_MAX_DISTANCE = 0.35;

    /** "하루 한 번" 가드의 하루 경계 기준 — 일기 월별 조회와 같은 KST. */
    private static final ZoneId ZONE = TimeZonePolicy.DEFAULT_ZONE_ID;

    private final RetrospectEngine engine;
    private final RetrospectRepository retrospectRepository;
    private final DiaryRepository diaryRepository;
    private final ActionCardRepository actionCardRepository;
    private final SituationEmbedder situationEmbedder;
    private final ActionCardEmbedder actionCardEmbedder;
    private final RetrospectStateCodec codec;
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final ScheduleRepository scheduleRepository;

    public RetrospectService(RetrospectEngine engine, RetrospectRepository retrospectRepository,
            DiaryRepository diaryRepository, ActionCardRepository actionCardRepository,
            SituationEmbedder situationEmbedder, ActionCardEmbedder actionCardEmbedder,
            RetrospectStateCodec codec, UserRepository userRepository,
            UserProfileRepository userProfileRepository, ScheduleRepository scheduleRepository) {
        this.engine = engine;
        this.retrospectRepository = retrospectRepository;
        this.diaryRepository = diaryRepository;
        this.actionCardRepository = actionCardRepository;
        this.situationEmbedder = situationEmbedder;
        this.actionCardEmbedder = actionCardEmbedder;
        this.codec = codec;
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.scheduleRepository = scheduleRepository;
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
        // 행동 추천 스텝에서만(지연) 부른다 — 이 사용자의 비슷한 상황 이전 카드를 찾는다.
        state.priorCardFinder(situation -> findSimilarCard(userId, situation));
        ReplyDto reply = engine.handle(state, command);
        sync(entity, state, reply);

        return reply;
    }

    /** 이 사용자의 카드 중 상황이 의미상 가장 비슷한 한 장 — 임베딩 실패·매칭 없음이면 empty. */
    private Optional<PriorActionCard> findSimilarCard(Long userId, String situation) {
        return situationEmbedder.embed(situation)
                .flatMap(vec -> actionCardRepository.findMostSimilar(userId, VectorLiteral.of(vec),
                        SIMILAR_MAX_DISTANCE))
                .map(c -> new PriorActionCard(c.getTargetAction(), c.getSituation(),
                        c.getCreatedDate()));
    }

    /** 완료된 회고의 일기 + 행동 카드 조회 — 그날의 일기를 다시 펼칠 때 쓴다. */
    @Transactional(readOnly = true)
    public RetrospectDiaryResult getDiary(Long userId, Long id) {
        requireSession(userId, id); // 소유권 검증 — 남의 회고 일기는 못 연다.
        Diary diary = diaryRepository.findByRetrospectId(id).orElse(null);
        ActionCard card = actionCardRepository.findByRetrospectId(id).orElse(null);
        return RetrospectDiaryResult.from(diary, card);
    }

    private void sync(Retrospect entity, RetrospectState state, ReplyDto reply) {
        Phase phase = state.phase();
        Instant completedAt = phase.isTerminal() ? Instant.now() : null;
        entity.sync(RetrospectStatus.from(phase), state.mode(), codec.serialize(state),
                completedAt);
        persistDiary(entity, state, reply);
        persistActionCard(entity, state, reply);
    }

    /**
     * 완료 턴에 만들어진 일기를 diaries 테이블에 한 벌 남긴다 — 이전엔 회고 컬럼이었다. 진입 감정
     * 두 종(현재·일정)을 함께 담아 나중의 월별 조회에서 바로 쓴다. 회고 한 벌에 일기 하나라,
     * 이미 있으면 다시 만들지 않는다(완료 턴에 한 번만 생긴다).
     */
    private void persistDiary(Retrospect entity, RetrospectState state, ReplyDto reply) {
        ReplyDto.DiaryDto diary = reply.diary();
        if (diary == null || diaryRepository.existsByRetrospectId(entity.getId())) {
            return;
        }
        diaryRepository.save(Diary.create(entity.getUserId(), entity.getId(), diary.diary(),
                diary.reframedDiary(), state.currentEmotion(), state.scheduleEmotion()));
    }

    /**
     * 회고가 만든 행동 카드를 영속화한다 — 이전엔 응답에만 실려 나가고 버려지던 것이다.
     * 회고 한 벌에 카드 한 장이라, 이미 있으면 다시 만들지 않는다(완료 턴에 한 번만 생긴다).
     *
     * <p>상황 임베딩은 <b>카드가 커밋된 뒤</b> 별도로 채운다({@link #scheduleEmbedding}). 임베딩은
     * 부가물이라, 예전처럼 같은 트랜잭션에서 채우면 임베딩 저장이 터질 때 카드까지 롤백돼 사라진다.
     */
    private void persistActionCard(Retrospect entity, RetrospectState state, ReplyDto reply) {
        ReplyDto.ActionCardDto card = reply.actionCard();
        if (card == null || actionCardRepository.existsByRetrospectId(entity.getId())) {
            return;
        }
        // 사용자가 최종 선택한 행동이 '쉬는 방법 선호'로 만든 카드였는지 — 분석용 내부 표식(비노출).
        boolean fromRestPreference = state.chosenAction() != null
                && state.chosenAction().restPreference();
        ActionCard saved = actionCardRepository.save(ActionCard.create(entity.getUserId(),
                entity.getId(), card.situation(), card.action(), LocalDate.now(),
                fromRestPreference));
        scheduleEmbedding(saved.getId(), card.situation());
    }

    /**
     * 카드가 커밋된 뒤 상황 임베딩을 채운다 — 다음 회고에서 "비슷한 상황"으로 되살릴 열쇠다.
     * 커밋 전에 채우면 카드 행이 아직 안 보여 헛돌므로 {@code afterCommit} 에 건다. 실패해도 삼킨다
     * ({@link ActionCardEmbedder} 가 별도 트랜잭션이라 카드 트랜잭션엔 영향이 없다).
     */
    private void scheduleEmbedding(Long cardId, String situation) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            embedQuietly(cardId, situation); // 트랜잭션 밖(방어) — 바로 시도한다.
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                embedQuietly(cardId, situation);
            }
        });
    }

    private void embedQuietly(Long cardId, String situation) {
        try {
            actionCardEmbedder.embedAndStore(cardId, situation);
        } catch (Exception e) {
            log.warn("행동 카드 임베딩 저장 실패(카드는 유지됨) cardId={}: {}", cardId, e.toString());
        }
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
        LocalDate today = LocalDate.now(ZONE);
        Instant start = today.atStartOfDay(ZONE).toInstant();
        Instant end = today.plusDays(1).atStartOfDay(ZONE).toInstant();
        if (diaryRepository.existsByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                userId, start, end)) {
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
