-- 저장한 성향을 대화 시작에 반영하는 실험의 집단과, 지난 대화들에서 도출된 시작 성향을 대화별 관찰에 남긴다.
-- apply_arm: NONE(실험에 들지 않음 — 반영 기능이 꺼져 있거나 대상이 아님) · TREATMENT(반영 켬) · CONTROL(반영 끔).
-- derived_* 는 집단과 무관하게 남긴다 — 실제로 반영된 대화는 apply_arm = 'TREATMENT' 이면서 derived_* 가 참인 대화다.
ALTER TABLE retrospect_disposition_observations
    ADD COLUMN apply_arm VARCHAR(20) NOT NULL DEFAULT 'NONE',
    ADD COLUMN derived_self_led BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN derived_other_view BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT chk_retrospect_disposition_observations_arm
        CHECK (apply_arm IN ('NONE', 'TREATMENT', 'CONTROL'));
