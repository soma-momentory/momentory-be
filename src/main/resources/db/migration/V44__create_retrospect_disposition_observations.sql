-- 전략 대화에서 턴마다 바바가 그때의 기분을 물었는지 남긴다 — 다음 답변이 그 물음에 대한 반응이다(감정 강도 성향의 재료).
-- 전략 선택을 켜지 않은 턴은 비어 있다.
ALTER TABLE retrospect_turn_judgments
    ADD COLUMN asked_feeling BOOLEAN;

-- 대화 한 건에서 관찰한 성향 신호의 횟수 — 회고마다 한 줄이고 답변이 올 때마다 더해진다.
-- 성향 값(어느 쪽인지)은 담지 않는다. 기준과 가중치가 정해지면 이 줄들을 읽어 계산한다. 발화 원문은 넣지 않는다.
CREATE TABLE retrospect_disposition_observations (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    retrospect_id BIGINT NOT NULL,
    observed_turns INTEGER NOT NULL,
    -- 주도성: 바바의 질문 · 제안 뒤의 저항(답하지 않음 · 화제 전환 · 반박)
    resistance_count INTEGER NOT NULL,
    -- 지지 대 도전: 명시적 요청만
    listen_request_count INTEGER NOT NULL,
    other_view_request_count INTEGER NOT NULL,
    -- 감정 강도: 바바가 기분을 물은 횟수와, 바로 다음 답에서 감정을 분명히 말한 / 감정 없이 답하거나 넘어간 횟수
    feeling_question_count INTEGER NOT NULL,
    feeling_expressed_count INTEGER NOT NULL,
    feeling_avoided_count INTEGER NOT NULL,
    awaiting_feeling_answer BOOLEAN NOT NULL,
    -- 신호를 셀 때 쓴 확신 기준과 마지막으로 적용된 규칙 버전
    min_confidence DOUBLE PRECISION NOT NULL,
    policy_version VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_retrospect_disposition_observations_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_retrospect_disposition_observations_retrospect
        FOREIGN KEY (retrospect_id) REFERENCES retrospects (id) ON DELETE CASCADE,
    CONSTRAINT uq_retrospect_disposition_observations_retrospect UNIQUE (retrospect_id),
    CONSTRAINT chk_retrospect_disposition_observations_counts CHECK (
        observed_turns >= 0 AND resistance_count >= 0 AND listen_request_count >= 0
        AND other_view_request_count >= 0 AND feeling_question_count >= 0
        AND feeling_expressed_count >= 0 AND feeling_avoided_count >= 0
        AND feeling_expressed_count + feeling_avoided_count <= feeling_question_count)
);

CREATE INDEX idx_retrospect_disposition_observations_user
    ON retrospect_disposition_observations (user_id, created_at);
