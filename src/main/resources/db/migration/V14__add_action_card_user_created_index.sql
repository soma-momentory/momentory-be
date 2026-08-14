-- 보관함 월별 조회 경로 — (user_id, created_at) 범위 스캔용. 일기의 idx_diaries_user_created 와 짝.
CREATE INDEX idx_action_cards_user_created ON action_cards (user_id, created_at);
