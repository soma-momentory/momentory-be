-- 바바의 편지 — 일기 한 벌에 편지 한 통. 회고를 마친 트랜잭션에서 PENDING 행을 먼저 남기고,
-- 커밋 뒤 뒤에서 만든다. 서버가 재시작돼도 PENDING 행이 남아 열 때 보충한다.
CREATE TABLE letters (
    id                    BIGSERIAL PRIMARY KEY,
    user_id               BIGINT       NOT NULL,
    diary_id              BIGINT       NOT NULL,
    letter_date           DATE         NOT NULL,
    status                VARCHAR(20)  NOT NULL,
    needs                 VARCHAR(200),
    needs_source          VARCHAR(20),
    quote                 VARCHAR(200),
    insight               TEXT,
    tonight_question      VARCHAR(200),
    stale                 BOOLEAN      NOT NULL DEFAULT FALSE,
    attempt_count         INT          NOT NULL DEFAULT 0,
    prompt_version        VARCHAR(20),
    generation_started_at TIMESTAMP WITH TIME ZONE,
    generated_at          TIMESTAMP WITH TIME ZONE,
    opened_at             TIMESTAMP WITH TIME ZONE,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_letters_user  FOREIGN KEY (user_id)  REFERENCES users (id)   ON DELETE CASCADE,
    CONSTRAINT fk_letters_diary FOREIGN KEY (diary_id) REFERENCES diaries (id) ON DELETE CASCADE,
    CONSTRAINT uk_letters_diary UNIQUE (diary_id),
    CONSTRAINT uk_letters_user_date UNIQUE (user_id, letter_date),
    CONSTRAINT ck_letters_status CHECK (status IN
        ('PENDING', 'GENERATING', 'READY', 'FAILED', 'FALLBACK', 'QUIET')),
    CONSTRAINT ck_letters_needs_source CHECK (needs_source IN ('WISH_CARD', 'INFERRED', 'DEFAULT'))
);

CREATE TABLE letter_actions (
    id          BIGSERIAL PRIMARY KEY,
    letter_id   BIGINT       NOT NULL,
    position    SMALLINT     NOT NULL,
    action_id   VARCHAR(10),
    title       VARCHAR(100) NOT NULL,
    source      VARCHAR(20)  NOT NULL,
    when_label  VARCHAR(30),
    schedule_id BIGINT,
    chosen      BOOLEAN      NOT NULL DEFAULT FALSE,
    remind_at   TIMESTAMP WITH TIME ZONE,
    result      VARCHAR(20),
    result_at   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_letter_actions_letter   FOREIGN KEY (letter_id)   REFERENCES letters (id)   ON DELETE CASCADE,
    CONSTRAINT fk_letter_actions_schedule FOREIGN KEY (schedule_id) REFERENCES schedules (id) ON DELETE SET NULL,
    CONSTRAINT uk_letter_actions_position UNIQUE (letter_id, position),
    CONSTRAINT ck_letter_actions_position CHECK (position BETWEEN 0 AND 2),
    CONSTRAINT ck_letter_actions_source CHECK (source IN ('CATALOG', 'FALLBACK', 'WISH_CARD')),
    CONSTRAINT ck_letter_actions_result CHECK (result IN ('DONE', 'NOT_DONE', 'SKIPPED'))
);

-- 한 편지에서 고른 작은 일은 하나뿐이다.
CREATE UNIQUE INDEX uk_letter_actions_chosen ON letter_actions (letter_id) WHERE chosen;

-- 편지 알림 시각 — null 은 아직 고르지 않았다는 뜻이다(앱은 08:30 으로 보여 준다).
ALTER TABLE user_profiles
    ADD COLUMN letter_time TIME,
    ADD COLUMN letter_notification_enabled BOOLEAN NOT NULL DEFAULT TRUE;
