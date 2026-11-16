-- 편지 안전 게이트가 고른 정책(NORMAL · GENTLE · QUIET). 게이트가 꺼져 있던 편지는 비어 있다.
-- 뒤에서 만들기가 실패해도 정책은 남겨, 열 때 보충이 판정을 다시 부르지 않고 같은 정책으로 만든다.
ALTER TABLE letters ADD COLUMN safety_policy VARCHAR(10);
ALTER TABLE letters ADD CONSTRAINT chk_letters_safety_policy
    CHECK (safety_policy IS NULL OR safety_policy IN ('NORMAL', 'GENTLE', 'QUIET'));
