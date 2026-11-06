-- 쉼터 「주간 행복」 — 한 주(일~토 · 일요일 04:00 KST 에 바뀐다)의 일곱 칸에 적은 글.
-- 한 칸에 글 하나다. 비운 칸은 행이 없다. 본문은 일기와 같은 등급의 사용자 글이다.
CREATE TABLE weekly_happiness_entries (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    week_start DATE NOT NULL,
    category VARCHAR(20) NOT NULL,
    content VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_weekly_happiness_entries_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_weekly_happiness_entries_user_week_category UNIQUE (user_id, week_start, category),
    CONSTRAINT chk_weekly_happiness_entries_category
        CHECK (category IN ('MOMENT', 'FOOD', 'CONTENT', 'PLACE', 'PERSON', 'PROUD', 'REST')),
    CONSTRAINT chk_weekly_happiness_entries_week_start_sunday
        CHECK (EXTRACT(ISODOW FROM week_start) = 7),
    CONSTRAINT chk_weekly_happiness_entries_content_not_blank
        CHECK (btrim(content) <> '')
);
