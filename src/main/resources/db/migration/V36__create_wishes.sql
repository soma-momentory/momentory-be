CREATE TABLE wishes (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    wish_date DATE NOT NULL,
    content VARCHAR(120) NOT NULL,
    light BOOLEAN NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    taken_down_at TIMESTAMP WITH TIME ZONE,
    deleted_at TIMESTAMP WITH TIME ZONE,
    outcome VARCHAR(20),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_wishes_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_wishes_user_date UNIQUE (user_id, wish_date),
    CONSTRAINT chk_wishes_outcome CHECK (outcome IN ('CAME_TRUE', 'NOT_YET'))
);

CREATE INDEX idx_wishes_floating ON wishes (expires_at)
    WHERE deleted_at IS NULL AND taken_down_at IS NULL;

CREATE TABLE wish_joins (
    id BIGSERIAL PRIMARY KEY,
    wish_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_wish_joins_wish FOREIGN KEY (wish_id) REFERENCES wishes (id) ON DELETE CASCADE,
    CONSTRAINT fk_wish_joins_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_wish_joins UNIQUE (wish_id, user_id)
);

CREATE TABLE wish_reports (
    id BIGSERIAL PRIMARY KEY,
    wish_id BIGINT NOT NULL,
    reporter_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_wish_reports_wish FOREIGN KEY (wish_id) REFERENCES wishes (id) ON DELETE CASCADE,
    CONSTRAINT fk_wish_reports_reporter FOREIGN KEY (reporter_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_wish_reports_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_wish_reports UNIQUE (wish_id, reporter_id)
);

CREATE INDEX idx_wish_reports_reporter_author ON wish_reports (reporter_id, author_id);

CREATE TABLE wish_rule_agreements (
    user_id BIGINT PRIMARY KEY,
    version INT NOT NULL,
    agreed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_wish_rule_agreements_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

ALTER TABLE rest_records DROP CONSTRAINT chk_rest_records_content;
ALTER TABLE rest_records ADD CONSTRAINT chk_rest_records_content
    CHECK (content IN ('SENSE_POND', 'WORRY_BOX', 'TREASURE_BOX', 'BODY_RELEASE', 'MEDITATION', 'WISH'));
