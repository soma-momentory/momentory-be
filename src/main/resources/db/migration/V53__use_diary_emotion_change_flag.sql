-- 변경 시각 대신 최초 분류를 제거한 저장 이력 여부만 보관한다.
-- V52에서 이미 기록한 변경 이력은 유지한다.
ALTER TABLE diaries ADD COLUMN emotion_classification_changed BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE diaries SET emotion_classification_changed = TRUE WHERE emotion_classification_changed_at IS NOT NULL;
ALTER TABLE diaries DROP CONSTRAINT ck_diary_emotion_change_snapshot;
ALTER TABLE diaries DROP COLUMN emotion_classification_changed_at;
ALTER TABLE diaries ADD CONSTRAINT ck_diary_emotion_change_snapshot CHECK (
    NOT emotion_classification_changed OR
    (retrospect_id IS NOT NULL AND original_emotion_selection_json IS NOT NULL AND emotion_selection_json IS NOT NULL)
);
