package com.momentory.retrospect.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.momentory.retrospect.domain.RetrospectStatus;

public interface RetrospectRepository extends JpaRepository<Retrospect, Long> {

    /** 소유권을 함께 검증하는 조회 — 남의 세션을 못 열도록. */
    Optional<Retrospect> findByIdAndUserId(Long id, Long userId);

    List<Retrospect> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 이 시각 뒤에 시작한 세션 중 가장 최근 것 — 앱을 다시 켰을 때 이어서 할 회고를 찾는다. */
    Optional<Retrospect> findFirstByUserIdAndStatusAndCreatedAtGreaterThanEqualOrderByCreatedAtDescIdDesc(
            Long userId, RetrospectStatus status, Instant from);
}
