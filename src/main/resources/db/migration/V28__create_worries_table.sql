CREATE TABLE worries (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    worry_date DATE NOT NULL,
    content VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_worries_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_worries_user_created ON worries (user_id, created_at);
