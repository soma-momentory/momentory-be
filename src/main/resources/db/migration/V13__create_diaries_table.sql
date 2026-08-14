-- 회고의 일기를 별도 테이블로 분리한다. 일기 조회가 잦고(그날의 일기 화면), 나중에 월별 조회도
-- 붙을 예정이라 회고 진행 상태(retrospects)와 떼어 독립적으로 읽는다. 행동 카드(action_cards)와
-- 같은 결의 분리다: retrospect_id 로 그날의 회고와 이어지고, user_id·created_at 으로 월별을 훑는다.
--
-- 기존 diary/reframed_diary 는 original/reframed 로 옮긴다(reframed 는 짧은 기록형에서 NULL).
-- 진입 감정 두 종도 함께 둔다: current_emotion(필수), schedule_emotion(특정 일정 없거나 '오늘
-- 하루' 회고면 NULL).
CREATE TABLE diaries (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    retrospect_id BIGINT NOT NULL,
    original TEXT NOT NULL,
    reframed TEXT,
    current_emotion VARCHAR(30) NOT NULL,
    schedule_emotion VARCHAR(30),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_diaries_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_diaries_retrospect FOREIGN KEY (retrospect_id) REFERENCES retrospects (id),
    CONSTRAINT uk_diaries_retrospect UNIQUE (retrospect_id)
);

-- 월별 조회(사용자 + 기간) 대비 인덱스.
CREATE INDEX idx_diaries_user_created ON diaries (user_id, created_at);

-- 백필 — 일기가 남아 있던 완료 회고를 옮긴다. current_emotion 은 retrospects 컬럼에서, 생성 시기는
-- 완료 시각(없으면 생성 시각)에서 가져온다. schedule_emotion 은 별도 컬럼이 없어(이전 마이그레이션에서
-- 제거됨) state_json 스냅샷에서 best-effort 로 뽑는다 — 없으면 NULL.
INSERT INTO diaries (user_id, retrospect_id, original, reframed, current_emotion,
                     schedule_emotion, created_at, updated_at)
SELECT user_id,
       id,
       diary,
       reframed_diary,
       current_emotion,
       (state_json::jsonb ->> 'scheduleEmotion'),
       COALESCE(completed_at, created_at),
       COALESCE(completed_at, created_at)
FROM retrospects
WHERE diary IS NOT NULL;

ALTER TABLE retrospects DROP COLUMN diary;
ALTER TABLE retrospects DROP COLUMN reframed_diary;
