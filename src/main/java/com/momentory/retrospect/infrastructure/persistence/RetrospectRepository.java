package com.momentory.retrospect.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.momentory.retrospect.domain.RetrospectStatus;

public interface RetrospectRepository extends JpaRepository<Retrospect, Long> {

    /** 소유권을 함께 검증하는 조회 — 남의 세션을 못 열도록. */
    Optional<Retrospect> findByIdAndUserId(Long id, Long userId);

    List<Retrospect> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 진행 중인 회고가 다시 열렸다고 남긴다 — 재개 열 두 개만 갱신한다. 대화 상태(state_json 등)는 건드리지 않아, 같은
     * 세션의 턴 처리와 겹쳐도 그 결과를 낡은 값으로 덮지 않는다. 조회한 사이에 끝난 세션이면 남기지 않는다(0 을 준다).
     */
    @Modifying
    @Query("update Retrospect r set r.resumeCount = r.resumeCount + 1, r.lastResumedAt = :at"
            + " where r.id = :id and r.status = com.momentory.retrospect.domain.RetrospectStatus.IN_PROGRESS")
    int recordResume(@Param("id") Long id, @Param("at") Instant at);

    /** 이 시각 뒤에 시작한 세션 중 가장 최근 것 — 앱을 다시 켰을 때 이어서 할 회고를 찾는다. */
    Optional<Retrospect> findFirstByUserIdAndStatusAndCreatedAtGreaterThanEqualOrderByCreatedAtDescIdDesc(
            Long userId, RetrospectStatus status, Instant from);
}
