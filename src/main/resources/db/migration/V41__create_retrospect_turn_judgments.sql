-- 회고 일기 작성 채팅에서 사용자 답변마다 판단 모델이 내린 여섯 항목의 판단 기록.
-- 발화 원문은 넣지 않는다 — 어느 발화인지는 회고 state_json 의 메시지 id 로 찾는다.
-- 판단 호출이 실패한 턴도 status = 'FAILED' 로 한 줄 남긴다(이때 판단 칸은 모두 비어 있다).
CREATE TABLE retrospect_turn_judgments (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    retrospect_id BIGINT NOT NULL,
    message_id VARCHAR(20) NOT NULL,
    diary_turn INTEGER NOT NULL,
    status VARCHAR(10) NOT NULL,
    model VARCHAR(50),
    questions_version VARCHAR(20),
    end_intent VARCHAR(40),
    end_intent_confidence DOUBLE PRECISION,
    requested_mode VARCHAR(40),
    requested_mode_confidence DOUBLE PRECISION,
    emotion_expression VARCHAR(40),
    emotion_expression_confidence DOUBLE PRECISION,
    event_info VARCHAR(40),
    event_info_confidence DOUBLE PRECISION,
    reaction VARCHAR(40),
    reaction_confidence DOUBLE PRECISION,
    experiencing_depth VARCHAR(40),
    experiencing_depth_confidence DOUBLE PRECISION,
    -- 항목 키별 선택지 확률 전체(JSON). 기준값을 나중에 정하고 보정을 분석하는 데 쓴다.
    probabilities_json TEXT,
    input_tokens INTEGER,
    latency_ms INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_retrospect_turn_judgments_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_retrospect_turn_judgments_retrospect
        FOREIGN KEY (retrospect_id) REFERENCES retrospects (id) ON DELETE CASCADE,
    CONSTRAINT uq_retrospect_turn_judgments_message UNIQUE (retrospect_id, message_id),
    CONSTRAINT chk_retrospect_turn_judgments_status CHECK (status IN ('JUDGED', 'FAILED')),
    CONSTRAINT chk_retrospect_turn_judgments_judged CHECK (
        status = 'FAILED' OR (model IS NOT NULL AND questions_version IS NOT NULL
            AND end_intent IS NOT NULL AND requested_mode IS NOT NULL
            AND emotion_expression IS NOT NULL AND event_info IS NOT NULL
            AND reaction IS NOT NULL AND experiencing_depth IS NOT NULL
            AND probabilities_json IS NOT NULL))
);

CREATE INDEX idx_retrospect_turn_judgments_user ON retrospect_turn_judgments (user_id, created_at);
