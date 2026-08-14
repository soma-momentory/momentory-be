package com.momentory.retrospect.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.RetrospectStatus;
import com.momentory.retrospect.domain.script.RetroMode;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;

/**
 * pgvector 최근접 검색 계약 — 실제 Postgres(pgvector)로 임베딩 저장·코사인 조회를 검증한다.
 *
 * <p>네이티브 SQL({@code CAST(:vec AS vector)}, {@code <=>})과 V8 마이그레이션(vector 컬럼 +
 * hnsw 인덱스)이 실제로 도는지는 여기서만 잡힌다 — 엔진 유닛 테스트는 저장소를 목킹한다.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ActionCardRepositoryIntegrationTest {

    private static final int DIM = 768;
    private static final double MAX_DISTANCE = 0.35;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired UserRepository userRepository;
    @Autowired RetrospectRepository retrospectRepository;
    @Autowired ActionCardRepository actionCardRepository;

    @Test
    void findsTheMostSimilarCardByEmbedding() {
        Long userId = userRepository.saveAndFlush(User.create()).getId();
        ActionCard interview = saveCard(userId, "면접에서 말문이 막힘", oneHot(0));
        saveCard(userId, "친구와 크게 다툼", oneHot(1));

        // 질의 벡터가 interview 와 같은 방향 → 코사인 거리 0, 다른 카드는 1
        Optional<ActionCard> match = actionCardRepository.findMostSimilar(userId, oneHot(0),
                MAX_DISTANCE);

        assertThat(match).isPresent();
        assertThat(match.get().getId()).isEqualTo(interview.getId());
    }

    @Test
    void returnsEmptyWhenNothingIsCloseEnough() {
        Long userId = userRepository.saveAndFlush(User.create()).getId();
        saveCard(userId, "면접에서 말문이 막힘", oneHot(0));

        // 직교 벡터 → 거리 1 > 임계값 0.35
        assertThat(actionCardRepository.findMostSimilar(userId, oneHot(5), MAX_DISTANCE)).isEmpty();
    }

    @Test
    void neverMatchesAnotherUsersCard() {
        Long mine = userRepository.saveAndFlush(User.create()).getId();
        Long other = userRepository.saveAndFlush(User.create()).getId();
        saveCard(other, "면접에서 말문이 막힘", oneHot(0));

        assertThat(actionCardRepository.findMostSimilar(mine, oneHot(0), MAX_DISTANCE)).isEmpty();
    }

    @Test
    void ignoresCardsWithoutEmbedding() {
        Long userId = userRepository.saveAndFlush(User.create()).getId();
        // 임베딩을 채우지 않은 카드 — 최근접에서 제외된다
        Long retrospectId = newRetrospect(userId);
        actionCardRepository.saveAndFlush(ActionCard.create(userId, retrospectId,
                "면접에서 말문이 막힘", "심호흡 세 번", LocalDate.of(2026, 7, 20), false));

        assertThat(actionCardRepository.findMostSimilar(userId, oneHot(0), MAX_DISTANCE)).isEmpty();
    }

    private ActionCard saveCard(Long userId, String situation, String embedding) {
        Long retrospectId = newRetrospect(userId);
        ActionCard card = actionCardRepository.saveAndFlush(ActionCard.create(userId, retrospectId,
                situation, "심호흡 세 번", LocalDate.of(2026, 7, 20), false));
        actionCardRepository.updateEmbedding(card.getId(), embedding);
        return card;
    }

    private Long newRetrospect(Long userId) {
        return retrospectRepository.saveAndFlush(Retrospect.start(userId,
                RetrospectStatus.IN_PROGRESS, RetroMode.REFRAME, null,
                Emotion.DEPRESSED, "{}")).getId();
    }

    /** 한 축만 1인 768차원 단위 벡터 리터럴 — 코사인 거리 검증용. */
    private static String oneHot(int hotIndex) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < DIM; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(i == hotIndex ? "1" : "0");
        }
        return sb.append(']').toString();
    }
}
