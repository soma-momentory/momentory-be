-- 사용자 선택과 최초 분류를 분리한다. 기존 일기는 회고/사건 원본에서 읽고 최초 수정 시 스냅샷을 남긴다.
ALTER TABLE diaries ADD COLUMN original_emotion_selection_json TEXT;
ALTER TABLE diaries ADD COLUMN emotion_selection_json TEXT;
ALTER TABLE diaries ADD COLUMN emotion_classification_changed_at TIMESTAMPTZ;
ALTER TABLE diaries ADD CONSTRAINT ck_diary_emotion_change_snapshot CHECK (
    emotion_classification_changed_at IS NULL OR
    (retrospect_id IS NOT NULL AND original_emotion_selection_json IS NOT NULL AND emotion_selection_json IS NOT NULL)
);
