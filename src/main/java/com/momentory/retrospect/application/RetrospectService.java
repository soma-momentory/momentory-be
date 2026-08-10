package com.momentory.retrospect.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import com.momentory.retrospect.domain.Phase;
import com.momentory.retrospect.domain.PriorActionCard;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.RetrospectStatus;
import com.momentory.retrospect.domain.assistant.SituationEmbedder;
import com.momentory.retrospect.infrastructure.persistence.ActionCard;
import com.momentory.retrospect.infrastructure.persistence.ActionCardRepository;
import com.momentory.retrospect.infrastructure.persistence.Retrospect;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.retrospect.infrastructure.persistence.RetrospectStateCodec;
import com.momentory.user.application.AuthenticatedUserNotFoundException;
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

    /**
     * 코사인 거리 임계값 — 이 미만이어야 "비슷한 상황"으로 본다(0=동일, 2=정반대).
     * 너무 크면 관계없는 카드가 추천되고, 너무 작으면 거의 안 걸린다. 튜닝 지점.
     */
    private static final double SIMILAR_MAX_DISTANCE = 0.35;

    private final RetrospectEngine engine;
    private final RetrospectRepository retrospectRepository;
    private final ActionCardRepository actionCardRepository;
    private final SituationEmbedder situationEmbedder;
    private final RetrospectStateCodec codec;
    private final UserRepository userRepository;

    public RetrospectService(RetrospectEngine engine, RetrospectRepository retrospectRepository,
            ActionCardRepository actionCardRepository, SituationEmbedder situationEmbedder,
            RetrospectStateCodec codec, UserRepository userRepository) {
        this.engine = engine;
        this.retrospectRepository = retrospectRepository;
        this.actionCardRepository = actionCardRepository;
        this.situationEmbedder = situationEmbedder;
        this.codec = codec;
        this.userRepository = userRepository;
    }

    /** 회고 시작 — 세션을 만들고 첫 메시지(공감 + 1턴 질문)를 낸다. */
    @Transactional
    public RetrospectResult start(Long userId, StartCommand command) {
        requireUser(userId);

        RetrospectState state = new RetrospectState(UUID.randomUUID().toString());
        ReplyDto reply = engine.start(state, command);

        Retrospect entity = Retrospect.start(userId, RetrospectStatus.from(state.phase()),
                state.mode(), state.schedule(), state.scheduleEmotion(), state.currentEmotion(),
                codec.serialize(state));
        retrospectRepository.save(entity);

        return new RetrospectResult(entity.getId(), reply);
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
                .flatMap(vec -> actionCardRepository.findMostSimilar(userId, toVectorLiteral(vec),
                        SIMILAR_MAX_DISTANCE))
                .map(c -> new PriorActionCard(c.getTargetAction(), c.getDetail(), c.getSituation(),
                        c.getCreatedDate()));
    }

    /** float[] → pgvector 리터럴 {@code "[0.1,0.2,...]"}. */
    private static String toVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder(vec.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }

    /** 완료된 회고의 일기 + 행동 카드 조회 — 그날의 일기를 다시 펼칠 때 쓴다. */
    @Transactional(readOnly = true)
    public RetrospectDiaryResult getDiary(Long userId, Long id) {
        Retrospect entity = requireSession(userId, id);
        ActionCard card = actionCardRepository.findByRetrospectId(id).orElse(null);
        return RetrospectDiaryResult.from(entity, card);
    }

    private void sync(Retrospect entity, RetrospectState state, ReplyDto reply) {
        String diary = null;
        String reframedDiary = null;
        if (reply.diary() != null) {
            diary = reply.diary().diary();
            reframedDiary = reply.diary().reframedDiary();
        }
        Phase phase = state.phase();
        Instant completedAt = phase.isTerminal() ? Instant.now() : null;
        entity.sync(RetrospectStatus.from(phase), state.mode(), codec.serialize(state),
                diary, reframedDiary, completedAt);
        persistActionCard(entity, reply);
    }

    /**
     * 회고가 만든 행동 카드를 영속화한다 — 이전엔 응답에만 실려 나가고 버려지던 것이다.
     * 회고 한 벌에 카드 한 장이라, 이미 있으면 다시 만들지 않는다(완료 턴에 한 번만 생긴다).
     */
    private void persistActionCard(Retrospect entity, ReplyDto reply) {
        ReplyDto.ActionCardDto card = reply.actionCard();
        if (card == null || actionCardRepository.existsByRetrospectId(entity.getId())) {
            return;
        }
        ActionCard saved = actionCardRepository.save(ActionCard.create(entity.getUserId(),
                entity.getId(), card.situation(), card.action(), card.detail(), LocalDate.now()));
        // 상황을 임베딩해 저장한다 — 다음 회고에서 "비슷한 상황"으로 되살릴 열쇠. 실패해도 넘어간다.
        situationEmbedder.embed(card.situation())
                .ifPresent(vec -> actionCardRepository.updateEmbedding(saved.getId(),
                        toVectorLiteral(vec)));
    }

    private Retrospect requireSession(Long userId, Long id) {
        return retrospectRepository.findByIdAndUserId(id, userId)
                .orElseThrow(RetrospectSessionNotFoundException::new);
    }

    private void requireUser(Long userId) {
        userRepository.findById(userId)
                .orElseThrow(AuthenticatedUserNotFoundException::new);
    }
}
