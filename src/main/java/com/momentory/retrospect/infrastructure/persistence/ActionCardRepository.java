package com.momentory.retrospect.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActionCardRepository extends JpaRepository<ActionCard, Long> {

    /** 그날의 일기(회고)에 붙은 행동 카드 — 회고 한 벌에 한 장뿐이다. */
    Optional<ActionCard> findByRetrospectId(Long retrospectId);

    boolean existsByRetrospectId(Long retrospectId);

    /** 단건 조회 + 소유권 검증 — 남의 카드는 못 연다. */
    Optional<ActionCard> findByIdAndUserId(Long id, Long userId);

    /**
     * 한 달치 행동 카드 — {@code [start, end)} 반열림 구간(월 경계는 서비스가 KST 로 계산한다).
     * 일기 월별 조회와 같은 {@code created_at} 기준이라, 달력에서 그날의 일기와 나란히 묶인다.
     * 최신순. {@code idx_action_cards_user_created (user_id, created_at)} 를 탄다.
     */
    List<ActionCard> findByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
            Long userId, Instant start, Instant end);

    /**
     * 상황 임베딩을 저장한다 — 카드 저장 직후 별도로 채운다(JPA 엔티티는 벡터 컬럼을 매핑하지
     * 않는다). {@code vec} 는 pgvector 리터럴 문자열({@code "[0.1,0.2,...]"}).
     */
    @Modifying
    @Query(value = "UPDATE action_cards SET situation_embedding = CAST(:vec AS vector) WHERE id = :id",
            nativeQuery = true)
    void updateEmbedding(@Param("id") Long id, @Param("vec") String vec);

    /**
     * 이 사용자의 카드 중 상황 임베딩이 {@code vec} 에 코사인으로 가장 가까운 한 장 —
     * 거리가 {@code maxDistance} 미만일 때만. 임베딩이 없는 카드는 제외한다.
     */
    @Query(value = """
            SELECT id, user_id, retrospect_id, situation, target_action,
                   created_date, from_rest_preference, done, done_at, reflection,
                   created_at, updated_at
            FROM action_cards
            WHERE user_id = :userId
              AND situation_embedding IS NOT NULL
              AND (situation_embedding <=> CAST(:vec AS vector)) < :maxDistance
            ORDER BY situation_embedding <=> CAST(:vec AS vector)
            LIMIT 1
            """, nativeQuery = true)
    Optional<ActionCard> findMostSimilar(@Param("userId") Long userId, @Param("vec") String vec,
            @Param("maxDistance") double maxDistance);
}
