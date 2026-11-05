-- 주간 리포트 v2 의 AI 글 — 그 주를 처음 열 때 만들어 저장한다. 사실(감정 · 일정 · 쉼터)은 저장하지 않고
-- 열 때마다 센다. source_digest 가 지금 일기와 다르면(고침 · 삭제) 다음에 열 때 다시 만든다.
CREATE TABLE weekly_report_writings (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT      NOT NULL,
    week_start     DATE        NOT NULL,
    cover_line     VARCHAR(200) NOT NULL,
    review         TEXT,
    good_words     TEXT,
    ok_line        VARCHAR(200),
    source_digest  VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(20) NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_weekly_report_writings_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uk_weekly_report_writings_user_week UNIQUE (user_id, week_start)
);
