-- 턴 판단으로 고른 전략을 판단 기록 옆에 남긴다. 전략 선택을 켜지 않았거나 판단이 실패한 턴은 모두 비어 있다.
-- generation_failed 는 그 턴의 문장 생성 모델 호출이 실패해 고정 문구로 응답했는지다 — 분석에서 이 턴을 가려낸다.
-- strategy_applied 는 바바의 응답이 실제로 그 전략대로 나갔는지다(턴 상한 · 단답 체크인 등이 앞서면 false).
ALTER TABLE retrospect_turn_judgments
    ADD COLUMN strategy_type VARCHAR(20),
    ADD COLUMN question_focus VARCHAR(20),
    ADD COLUMN question_as_choices BOOLEAN,
    ADD COLUMN policy_version VARCHAR(20),
    ADD COLUMN strategy_applied BOOLEAN,
    ADD COLUMN generation_failed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT chk_retrospect_turn_judgments_strategy CHECK (
        (strategy_type IS NULL AND question_focus IS NULL AND question_as_choices IS NULL
            AND policy_version IS NULL AND strategy_applied IS NULL)
        OR (status = 'JUDGED' AND strategy_type IS NOT NULL AND question_focus IS NOT NULL
            AND question_as_choices IS NOT NULL AND policy_version IS NOT NULL
            AND strategy_applied IS NOT NULL));
