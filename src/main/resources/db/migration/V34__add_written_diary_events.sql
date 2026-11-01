-- 직접 쓴 일기의 사건 이름·요약·본문 근거 감정. 바바 편지의 행동 추천 재료로 재사용한다.
-- 기존 일기는 NULL을 유지한다. 일기에 속한 컬럼이므로 삭제·계정 삭제와 수명이 같다.
ALTER TABLE diaries ADD COLUMN events_json TEXT;
