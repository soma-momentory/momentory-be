-- 직접 쓴 일기도 보관함에 남긴다. 기존 회고 FK와 회고당 한 장 UNIQUE는 유지한다.
ALTER TABLE diaries ALTER COLUMN retrospect_id DROP NOT NULL;
