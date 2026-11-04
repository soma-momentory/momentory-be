ALTER TABLE rest_records
    DROP CONSTRAINT chk_rest_records_content;

ALTER TABLE rest_records
    ADD CONSTRAINT chk_rest_records_content
        CHECK (content IN ('SENSE_POND', 'WORRY_BOX', 'TREASURE_BOX', 'BODY_RELEASE', 'MEDITATION'));
