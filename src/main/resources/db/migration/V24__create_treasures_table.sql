CREATE TABLE treasures (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    treasure_date DATE NOT NULL,
    content VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_treasures_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_treasures_user_date_created ON treasures (user_id, treasure_date, created_at);
