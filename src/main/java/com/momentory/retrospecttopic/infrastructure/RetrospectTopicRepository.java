package com.momentory.retrospecttopic.infrastructure;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.momentory.retrospecttopic.domain.RetrospectTopic;

/**
 * 회고 토픽(주 일정·키워드) 저장·조회. 쓰기는 회고 완료 이벤트 리스너가, 읽기는 주간 리포트가 쓴다.
 */
public interface RetrospectTopicRepository extends JpaRepository<RetrospectTopic, Long> {

    boolean existsByRetrospectId(Long retrospectId);

    /** 그 회고가 남긴 토픽들 — 감정 재저장(중복 발행) 방어·조회에 쓴다. */
    List<RetrospectTopic> findByRetrospectId(Long retrospectId);

    /**
     * 이 사용자가 {@code [start, end]} 구간에 남긴 토픽 — 주간 리포트의 키워드·감정 집계 재료.
     * {@code idx_rt_user_date (user_id, created_date)} 를 탄다.
     */
    List<RetrospectTopic> findByUserIdAndCreatedDateBetween(Long userId, LocalDate start,
            LocalDate end);
}
