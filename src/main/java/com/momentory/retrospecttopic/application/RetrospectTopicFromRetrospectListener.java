package com.momentory.retrospecttopic.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.momentory.common.time.DayBoundary;
import com.momentory.retrospect.application.RetrospectCompleted;
import com.momentory.retrospect.domain.TopicType;
import com.momentory.retrospecttopic.domain.RetrospectTopic;
import com.momentory.retrospecttopic.infrastructure.RetrospectTopicRepository;

/**
 * 회고 완료 이벤트를 받아 토픽(주 일정·키워드 + 매칭 감정)을 남기는 retrospecttopic 컨텍스트의
 * 진입점 — 쓰기 방향에서 retrospect 와의 결합을 이벤트로 끊는다(retrospect 는 토픽을 어떻게 저장하는지
 * 모른다). diary·actioncard 리스너와 같은 결이다.
 *
 * <p><b>{@code @EventListener} 는 같은 트랜잭션에서 동기로 실행된다</b> — 저장 실패는 회고 턴째
 * 롤백된다. 회고 한 벌은 토픽을 한 번만 만들므로, 이미 있으면(중복 발행 방어) 아무것도 안 한다.
 *
 * <p>토픽 날짜({@code createdDate})는 04:00 하루 경계를 따른다 — 새벽 1시에 마친 회고의 토픽은
 * 어제로 집계된다(일기·행동 카드와 같은 기준).
 */
@Component
public class RetrospectTopicFromRetrospectListener {

    private final RetrospectTopicRepository repository;

    public RetrospectTopicFromRetrospectListener(RetrospectTopicRepository repository) {
        this.repository = repository;
    }

    @EventListener
    public void on(RetrospectCompleted event) {
        if (event.topics().isEmpty() || repository.existsByRetrospectId(event.retrospectId())) {
            return;
        }
        LocalDate date = DayBoundary.today();
        List<RetrospectTopic> topics = new ArrayList<>();
        for (RetrospectCompleted.TopicData t : event.topics()) {
            topics.add(toEntity(event.userId(), event.retrospectId(), date, t));
        }
        repository.saveAll(topics);
    }

    private static RetrospectTopic toEntity(Long userId, Long retrospectId, LocalDate date,
            RetrospectCompleted.TopicData t) {
        if (t.type() == TopicType.SCHEDULE) {
            return RetrospectTopic.schedule(userId, retrospectId, t.scheduleId(), t.label(), date,
                    t.emotions());
        }
        return RetrospectTopic.keyword(userId, retrospectId, t.label(), date, t.emotions());
    }
}
