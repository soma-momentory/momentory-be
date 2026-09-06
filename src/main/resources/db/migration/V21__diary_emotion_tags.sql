-- 회고 채팅 v2: 감정 없이 끝난 일기(감정 탐색 미진행 + 추출된 감정 없음)를 허용한다.
-- current_emotion 은 리포트가 쓰는 '대표 감정'으로 남되 nullable 로 바꾸고, 일기에서 드러난 감정
-- 전체를 태그(CSV 키)로 담는 emotions 컬럼을 추가한다(N:N 감정 태그의 저장 자리).

ALTER TABLE diaries
    ALTER COLUMN current_emotion DROP NOT NULL;

ALTER TABLE diaries
    ADD COLUMN emotions TEXT;
