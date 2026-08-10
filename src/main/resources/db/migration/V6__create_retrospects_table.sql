CREATE TABLE retrospects (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    mode VARCHAR(30),
    schedule VARCHAR(255),
    schedule_emotion VARCHAR(30),
    current_emotion VARCHAR(30) NOT NULL,
    state_json TEXT NOT NULL,
    diary TEXT,
    reframed_diary TEXT,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_retrospects_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_retrospects_user_created ON retrospects (user_id, created_at);
