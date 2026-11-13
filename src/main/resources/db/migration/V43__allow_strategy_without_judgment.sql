-- 판단 모델이 실패한 턴에도 전략은 고른다(지금까지의 신호로 기본값) — 그 전략을 기록에 남길 수 있게 한다.
-- 예전 제약은 전략 칸이 차 있으려면 status = 'JUDGED' 여야 했다. 전략 칸끼리 함께 차거나 함께 비는 것만 지킨다.
ALTER TABLE retrospect_turn_judgments
    DROP CONSTRAINT chk_retrospect_turn_judgments_strategy,
    ADD CONSTRAINT chk_retrospect_turn_judgments_strategy CHECK (
        (strategy_type IS NULL AND question_focus IS NULL AND question_as_choices IS NULL
            AND policy_version IS NULL AND strategy_applied IS NULL)
        OR (strategy_type IS NOT NULL AND question_focus IS NOT NULL
            AND question_as_choices IS NOT NULL AND policy_version IS NOT NULL
            AND strategy_applied IS NOT NULL));
