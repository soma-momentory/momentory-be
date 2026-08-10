package com.momentory.retrospect.application;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.momentory.retrospect.application.metering.UsageRecorder;
import com.momentory.retrospect.application.metering.UsageSummary;
import com.momentory.retrospect.domain.Phase;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.RetrospectStatus;
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

    private final RetrospectEngine engine;
    private final RetrospectRepository retrospectRepository;
    private final RetrospectStateCodec codec;
    private final UserRepository userRepository;
    private final UsageRecorder usage;

    public RetrospectService(RetrospectEngine engine, RetrospectRepository retrospectRepository,
            RetrospectStateCodec codec, UserRepository userRepository, UsageRecorder usage) {
        this.engine = engine;
        this.retrospectRepository = retrospectRepository;
        this.codec = codec;
        this.userRepository = userRepository;
        this.usage = usage;
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
        ReplyDto reply = engine.handle(state, command);
        sync(entity, state, reply);

        return reply;
    }

    /** 이번 세션의 LLM 사용량 요약(계측). 계측은 인메모리라 서버 재시작 시 사라진다. */
    @Transactional(readOnly = true)
    public UsageSummary usage(Long userId, Long id) {
        Retrospect entity = requireSession(userId, id);
        // 계측 키는 세션의 내부 id(스냅샷 안의 UUID)다 — 그걸 꺼내 요약을 조회한다.
        String sessionKey = codec.deserialize(entity.getStateJson()).id();
        return usage.summarize(sessionKey);
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
