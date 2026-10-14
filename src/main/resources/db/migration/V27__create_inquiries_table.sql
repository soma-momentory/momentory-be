-- 앱 안 문의하기. 관리 화면 없이 개발자가 DB 에서 직접 읽고, 답하면 status 를 ANSWERED 로 바꾼다.
CREATE TABLE inquiries (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    category VARCHAR(20) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    reply_email VARCHAR(254),
    platform VARCHAR(10),
    os_version VARCHAR(30),
    app_version VARCHAR(30),
    status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_inquiries_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_inquiries_category
        CHECK (category IN ('BUG', 'SUGGESTION', 'ACCOUNT', 'ETC')),
    CONSTRAINT chk_inquiries_platform
        CHECK (platform IN ('IOS', 'ANDROID')),
    CONSTRAINT chk_inquiries_status
        CHECK (status IN ('RECEIVED', 'ANSWERED'))
);

CREATE INDEX idx_inquiries_status_created ON inquiries (status, created_at);
