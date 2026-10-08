CREATE TABLE rest_records (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    content VARCHAR(20) NOT NULL,
    record_date DATE NOT NULL,
    completed BOOLEAN NOT NULL,
    mood VARCHAR(20),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rest_records_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_rest_records_content
        CHECK (content IN ('SENSE_POND', 'WORRY_BOX', 'TREASURE_BOX', 'BODY_RELEASE')),
    CONSTRAINT chk_rest_records_mood
        CHECK (mood IN ('BETTER', 'SAME', 'UNSURE')),
    CONSTRAINT chk_rest_records_completed_mood
        CHECK ((completed AND mood IS NOT NULL) OR (NOT completed AND mood IS NULL))
);

CREATE INDEX idx_rest_records_user_content_created ON rest_records (user_id, content, created_at);
