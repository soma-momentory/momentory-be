-- diaries.current_emotion 은 '회고 시작 감정'이 아니라 일기의 <b>대표 감정</b>(리포트·달력 대표 점)이다.
-- 이름이 개념과 어긋나 오해를 부르므로 primary_emotion 으로 바꾼다. 값·타입은 그대로.
ALTER TABLE diaries RENAME COLUMN current_emotion TO primary_emotion;
