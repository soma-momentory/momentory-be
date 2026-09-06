-- 회고 채팅 v2: 행동 카드를 '바람 카드'로 확장한다(결정 ①로 기존 행동 카드 대체).
-- 감정(키 CSV)·바람(단어 CSV)·바랐던 모습·감정 성격을 담는다. 상황·임베딩·완료(해봤어요)는 그대로 재사용.
-- 작은 행동(target_action)은 감정 탐색 중 '오늘은 여기까지'로 안 정할 수 있어 NOT NULL 을 푼다.

ALTER TABLE action_cards
    ADD COLUMN emotions TEXT,
    ADD COLUMN needs TEXT,
    ADD COLUMN desired_state TEXT,
    ADD COLUMN sentiment VARCHAR(20);

ALTER TABLE action_cards
    ALTER COLUMN target_action DROP NOT NULL;
