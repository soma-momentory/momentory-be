-- 기존 실험은 조건부 반영으로 보존하고, 세 차원 상시 반영 실험과 구분한다.
ALTER TABLE retrospect_disposition_observations
    ADD COLUMN derived_listen BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN derived_gentle_emotion BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN derived_history_sessions INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN disposition_version VARCHAR(30) NOT NULL DEFAULT 'conditional-v1',
    ADD COLUMN past_disposition_applied BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT chk_disposition_history_sessions CHECK (derived_history_sessions >= 0);

-- 기존 기록의 실제 반영 여부만 복원한다. 집단 배정/과거 관찰/도출값은 변경하지 않는다.
UPDATE retrospect_disposition_observations
SET past_disposition_applied = TRUE
WHERE apply_arm = 'TREATMENT' AND (derived_self_led OR derived_other_view);
