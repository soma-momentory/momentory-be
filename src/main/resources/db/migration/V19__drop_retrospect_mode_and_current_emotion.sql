-- 회고 채팅 v2: 대화가 모드 분기 없이 진행되고, 시작 시 감정을 고르지 않는다.
-- retrospects.mode(회고 모드)와 current_emotion(시작 감정) 컬럼을 제거한다.
-- 세션 진행에 필요한 값은 state_json 안에 있고, 완료 시 감정은 diaries 쪽에 저장된다.
-- current_emotion 은 NOT NULL 이었어서, v2 엔티티가 값을 주지 않으면 INSERT 가 깨진다 — 그래서 드롭한다.

ALTER TABLE retrospects
    DROP COLUMN mode;

ALTER TABLE retrospects
    DROP COLUMN current_emotion;
