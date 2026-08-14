-- 행동 카드의 target_action/detail 이원화를 없앤다 — 이제 상세 내용을 target_action 하나에 담는다.
-- 1) detail(TEXT) 내용을 담을 수 있도록 target_action 을 TEXT 로 넓힌다.
ALTER TABLE action_cards ALTER COLUMN target_action TYPE TEXT;
-- 2) 기존 detail 내용을 target_action 으로 옮긴다(내용이 있는 행만 — 없으면 기존 값 유지).
UPDATE action_cards SET target_action = detail WHERE detail IS NOT NULL AND detail <> '';
-- 3) detail 컬럼 제거.
ALTER TABLE action_cards DROP COLUMN detail;
