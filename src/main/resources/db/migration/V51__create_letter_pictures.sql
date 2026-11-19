-- 바바의 그림일기 — 편지 한 통에 4컷 그림 하나. 편지가 지워지면(일기 삭제 · 탈퇴) 함께 지워진다.
-- 그림은 무거워(JPEG 150KB 안팎) 편지 행과 따로 둔다. 행이 있다는 것이 「한 번 만들기에 들어갔다」는 표시라
-- 실패(FAILED)해도, 일기를 고쳐도 다시 만들지 않는다(비용). 토큰 수는 llm-usage 로그에 남는다.
CREATE TABLE letter_pictures (
    letter_id        BIGINT PRIMARY KEY,
    status           VARCHAR(16)  NOT NULL,
    image            BYTEA,
    mime_type        VARCHAR(32),
    scenes           TEXT,
    plan_model       VARCHAR(60),
    image_model      VARCHAR(60),
    prompt_version   VARCHAR(20)  NOT NULL,
    started_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    generated_at     TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_letter_pictures_letter FOREIGN KEY (letter_id) REFERENCES letters (id) ON DELETE CASCADE,
    CONSTRAINT ck_letter_pictures_status CHECK (status IN ('GENERATING', 'READY', 'FAILED')),
    CONSTRAINT ck_letter_pictures_ready CHECK (status <> 'READY' OR (image IS NOT NULL AND mime_type IS NOT NULL))
);
