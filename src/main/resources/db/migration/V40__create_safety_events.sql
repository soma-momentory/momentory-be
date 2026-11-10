-- 회고 중 위기 안내(RISK 이상)가 나간 기록. 편지를 만들 때와 열 때 「이 일기 날 이후 위기가 있었는가」를 본다.
-- 같은 회고 · 같은 등급은 한 줄만 남긴다(재전송 · 「이어서 얘기하기」 뒤 재상승). 일기 원문 · 근거 문구 · 플래그는 넣지 않는다.
CREATE TABLE safety_events (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    retrospect_id BIGINT NOT NULL,
    level VARCHAR(10) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_safety_events_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_safety_events_retrospect
        FOREIGN KEY (retrospect_id) REFERENCES retrospects (id) ON DELETE CASCADE,
    CONSTRAINT uq_safety_events_retrospect_level UNIQUE (retrospect_id, level),
    CONSTRAINT chk_safety_events_level CHECK (level IN ('RISK', 'IMMINENT'))
);

CREATE INDEX idx_safety_events_user_occurred ON safety_events (user_id, occurred_at);
