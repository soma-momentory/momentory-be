CREATE TABLE action_cards (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    retrospect_id BIGINT NOT NULL,
    situation VARCHAR(500) NOT NULL,
    target_action VARCHAR(255) NOT NULL,
    detail TEXT,
    created_date DATE NOT NULL,
    done BOOLEAN NOT NULL DEFAULT FALSE,
    done_at TIMESTAMP WITH TIME ZONE,
    reflection TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_action_cards_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_action_cards_retrospect FOREIGN KEY (retrospect_id) REFERENCES retrospects (id),
    -- 일기 한 장에 행동 카드 한 장 — 회고당 하나로 못박아 중복 삽입을 막는다
    CONSTRAINT uk_action_cards_retrospect UNIQUE (retrospect_id)
);

-- 같은 상황에서 만든 이전 카드를 먼저 보여주는 기능을 위한 조회 경로
CREATE INDEX idx_action_cards_user_situation ON action_cards (user_id, situation);
