ALTER TABLE schedules
    ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE schedules
SET source = 'CALENDAR'
WHERE external_id IS NOT NULL;

ALTER TABLE schedules
    ADD CONSTRAINT chk_schedules_source
        CHECK (source IN ('MANUAL', 'CALENDAR')),
    ADD CONSTRAINT chk_schedules_source_external_id
        CHECK (
            (source = 'MANUAL' AND external_id IS NULL)
            OR (source = 'CALENDAR' AND external_id IS NOT NULL)
        );
