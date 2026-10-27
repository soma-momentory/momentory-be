-- 회고에서 작은 행동을 고르지 않게 되면서(바바의 편지 PRD 14-1) 편지의 임시 규칙 「어젯밤 정한 일」을 지웠다.
-- 그 규칙으로 남은 칸은 연결표에서 온 행동과 같게 다룬다.
UPDATE letter_actions SET source = 'CATALOG' WHERE source = 'WISH_CARD';

ALTER TABLE letter_actions DROP CONSTRAINT ck_letter_actions_source;
ALTER TABLE letter_actions
    ADD CONSTRAINT ck_letter_actions_source CHECK (source IN ('CATALOG', 'FALLBACK'));
