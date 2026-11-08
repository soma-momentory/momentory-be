-- 0 = 아직 안 함, 1 = 옛 온보딩(v1 API), 2 = 새 온보딩(v2 API).
-- 이미 온보딩을 마친 사람은 옛 판을 마친 것으로 본다.
ALTER TABLE users
    ADD COLUMN onboarding_version INTEGER NOT NULL DEFAULT 0;

UPDATE users SET onboarding_version = 1 WHERE onboarding_completed = TRUE;

-- 새 온보딩 질문 답. 판마다 한 행이고, 건너뛴 질문은 NULL 이다.
CREATE TABLE user_onboarding_responses (
    user_id BIGINT NOT NULL,
    version INTEGER NOT NULL,
    mood_change VARCHAR(30),
    know_why VARCHAR(30),
    day_end VARCHAR(30),
    diary_history VARCHAR(30),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_user_onboarding_responses PRIMARY KEY (user_id, version),
    CONSTRAINT fk_user_onboarding_responses_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE user_onboarding_goals (
    user_id BIGINT NOT NULL,
    version INTEGER NOT NULL,
    goal VARCHAR(30) NOT NULL,
    CONSTRAINT pk_user_onboarding_goals PRIMARY KEY (user_id, version, goal),
    CONSTRAINT fk_user_onboarding_goals_response
        FOREIGN KEY (user_id, version) REFERENCES user_onboarding_responses (user_id, version) ON DELETE CASCADE
);
