-- 분석용 내부 플래그 — 이 행동 카드가 온보딩 '쉬는 방법' 선호를 반영해 만든 카드인지.
-- 감정 정리형의 쉬는 행동 카드(care_action)에서만 true 가 될 수 있다: AI 가 그 보기를 선호 기반으로
-- 표시했거나(정상 경로), 폴백이 선호로 직접 만든 카드를 사용자가 골랐을 때. 사용자에게는 노출하지 않는다.
ALTER TABLE action_cards
    ADD COLUMN from_rest_preference BOOLEAN NOT NULL DEFAULT FALSE;
