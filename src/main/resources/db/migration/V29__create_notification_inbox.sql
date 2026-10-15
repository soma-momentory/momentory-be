CREATE TABLE notifications (
    id BIGSERIAL PRIMARY KEY,
    type VARCHAR(40) NOT NULL,
    title VARCHAR(40) NOT NULL,
    body VARCHAR(500) NOT NULL,
    link VARCHAR(500),
    audience VARCHAR(10) NOT NULL,
    user_id BIGINT,
    published_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_notifications_title CHECK (char_length(title) BETWEEN 1 AND 40),
    CONSTRAINT chk_notifications_body CHECK (char_length(body) <= 500),
    CONSTRAINT chk_notifications_link CHECK (link IS NULL OR (left(link, 1) = '/' AND left(link, 2) <> '//' AND position(chr(92) in link) = 0)),
    CONSTRAINT chk_notifications_audience CHECK ((audience = 'ALL' AND user_id IS NULL) OR (audience = 'USER' AND user_id IS NOT NULL)),
    CONSTRAINT chk_notifications_critical_expiry CHECK (type <> 'CRITICAL_NOTICE' OR expires_at IS NOT NULL),
    CONSTRAINT chk_notifications_expiry CHECK (expires_at IS NULL OR expires_at > published_at)
);

CREATE INDEX idx_notifications_published ON notifications (published_at DESC, id DESC);
CREATE INDEX idx_notifications_user ON notifications (user_id) WHERE user_id IS NOT NULL;

CREATE TABLE notification_reads (
    user_id BIGINT NOT NULL,
    notification_id BIGINT NOT NULL,
    read_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, notification_id),
    CONSTRAINT fk_notification_reads_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_notification_reads_notification FOREIGN KEY (notification_id) REFERENCES notifications (id) ON DELETE CASCADE
);

CREATE INDEX idx_notification_reads_notification ON notification_reads (notification_id);
