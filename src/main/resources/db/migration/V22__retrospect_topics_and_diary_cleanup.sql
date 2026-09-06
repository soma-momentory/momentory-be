-- 회고 채팅 v2: 채팅 후에 뽑은 '주 일정'과 '키워드'(= 토픽)에 감정을 매칭해 남긴다.
-- 한 토픽에 감정이 여러 개일 수 있어(0개도 가능) 토픽과 감정을 두 테이블로 나눈다:
--   retrospect_topics          — 토픽 자체(주 일정/키워드). 주간 리포트 집계용 created_date 를 든다.
--   retrospect_topic_emotions  — 토픽 × 감정(한 쌍 = 한 행).
-- 주 일정은 일정 목록에서 온 것(schedule_id)과 채팅에서 뽑은 자유 텍스트 둘 다 가능하고,
-- 키워드는 누적/집계 대상이라 label 을 항상 텍스트로 든다(일정이 삭제돼도 집계가 살아남는다).

CREATE TABLE retrospect_topics (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    retrospect_id BIGINT       NOT NULL,
    topic_type    VARCHAR(20)  NOT NULL,   -- 'SCHEDULE' | 'KEYWORD'
    schedule_id   BIGINT,                  -- 목록 일정일 때만. 자유 텍스트/키워드면 NULL
    label         VARCHAR(255) NOT NULL,   -- 일정 제목 or 키워드 텍스트 (항상 존재). 일정 제목 상한(255)과 맞춘다
    created_date  DATE         NOT NULL,   -- 주간 리포트 기간 집계용
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rt_user       FOREIGN KEY (user_id)       REFERENCES users (id)       ON DELETE CASCADE,
    CONSTRAINT fk_rt_retrospect FOREIGN KEY (retrospect_id) REFERENCES retrospects (id) ON DELETE CASCADE,
    CONSTRAINT fk_rt_schedule   FOREIGN KEY (schedule_id)   REFERENCES schedules (id)   ON DELETE SET NULL
);

CREATE INDEX idx_rt_user_date  ON retrospect_topics (user_id, created_date);  -- 주간 집계
CREATE INDEX idx_rt_user_label ON retrospect_topics (user_id, label);         -- 키워드 누적
CREATE INDEX idx_rt_retrospect ON retrospect_topics (retrospect_id);          -- 회고별 조회/삭제

CREATE TABLE retrospect_topic_emotions (
    id       BIGSERIAL   PRIMARY KEY,
    topic_id BIGINT      NOT NULL,
    emotion  VARCHAR(30) NOT NULL,
    CONSTRAINT fk_rte_topic FOREIGN KEY (topic_id) REFERENCES retrospect_topics (id) ON DELETE CASCADE,
    CONSTRAINT uk_rte_topic_emotion UNIQUE (topic_id, emotion)   -- 같은 토픽에 같은 감정 중복 방지
);

CREATE INDEX idx_rte_emotion ON retrospect_topic_emotions (emotion);

-- 死컬럼 정리(회고 채팅 v2):
--   reframed         — 일기를 하나(original)로만 쓰기로 해서 리프레임 본문을 없앤다.
--   schedule_emotion — 시작 시 일정에 감정을 붙이던 경로를 없앴다(감정은 대화로 알아간다).
ALTER TABLE diaries DROP COLUMN reframed;
ALTER TABLE diaries DROP COLUMN schedule_emotion;
