-- 회고가 참조하던 일정 정보를 비정규화 스냅샷(schedule 이름 + schedule_emotion)에서
-- schedules 테이블의 id 참조로 바꾼다. 이름/감정은 세션 진행에 필요해 state_json 안에는 그대로 남고,
-- 여기서 지우는 것은 조회에 쓰이지 않던 별도 컬럼뿐이다.
-- 특정 일정 없는 '오늘 하루' 회고와 자유 입력 일정은 schedule_id 가 NULL 이라 nullable 로 둔다.
ALTER TABLE retrospects
    ADD COLUMN schedule_id BIGINT;

ALTER TABLE retrospects
    ADD CONSTRAINT fk_retrospects_schedule
        FOREIGN KEY (schedule_id) REFERENCES schedules (id);

ALTER TABLE retrospects
    DROP COLUMN schedule;

ALTER TABLE retrospects
    DROP COLUMN schedule_emotion;
