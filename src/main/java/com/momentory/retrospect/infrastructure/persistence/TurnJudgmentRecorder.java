package com.momentory.retrospect.infrastructure.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.momentory.retrospect.domain.judgment.JudgmentItem;
import com.momentory.retrospect.domain.judgment.TurnJudgmentLog;

import tools.jackson.databind.ObjectMapper;

/**
 * 턴 판단 기록을 저장한다 — 회고 턴 트랜잭션 안에서 부르므로 턴이 롤백되면 기록도 없다.
 * 선택지 확률은 항목 키별 JSON 으로 묶어 한 칸에 넣는다.
 */
@Component
public class TurnJudgmentRecorder {

    private final TurnJudgmentRecordRepository repository;
    private final ObjectMapper objectMapper;

    public TurnJudgmentRecorder(TurnJudgmentRecordRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void record(Long userId, Long retrospectId, List<TurnJudgmentLog> logs) {
        for (TurnJudgmentLog log : logs) {
            repository.save(TurnJudgmentRecord.of(userId, retrospectId, log, probabilitiesJson(log)));
        }
    }

    private String probabilitiesJson(TurnJudgmentLog log) {
        if (log.failed()) {
            return null;
        }
        Map<String, Map<String, Double>> byItem = new LinkedHashMap<>();
        for (JudgmentItem item : JudgmentItem.values()) {
            byItem.put(item.key(), log.judgment().of(item).probabilities());
        }
        return objectMapper.writeValueAsString(byItem);
    }
}
