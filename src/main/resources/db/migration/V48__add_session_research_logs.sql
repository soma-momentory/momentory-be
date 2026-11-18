-- 대화 전략 실험의 미완료 이탈과 질문 단위 지표를 재려고 회고 세션과 턴 기록에 남길 값을 더한다. 발화 원문은 담지 않는다.
--
-- retrospects
--   end_reason        회고가 어떻게 끝났는가. 끝나지 않았으면 NULL(이전 행도 NULL).
--                     WRAP_UP_ACCEPTED(마무리 제안·단답 체크인에서 정리를 고름) · NO_OTHER_MEMORY(다른 기억 질문에 없다고 답함) ·
--                     USER_REQUEST(사용자가 끝내려 함 — 판단 모델의 종료 판단 또는 종료 문구) · TURN_CAP(일기 작성 턴 상한) ·
--                     ABUSE_STOPPED(악용 반복으로 중단) · LEGACY_EXPLORATION(배포 전 감정 탐색 단계에 있던 세션을 정리).
--                     안전 대응으로 멈춘 세션은 끝난 것이 아니므로 여기에 없다 — safety_events 로 본다.
--   last_user_turn_at 끝나기 전 사용자의 마지막 턴(글 · 선택지) 시각.
--   resume_count      진행 중인 회고가 다시 열린 횟수 — 「이어서 하기」 조회(GET /current)가 이 회고를 돌려준 횟수다.
--                     이 열과 last_resumed_at 은 조회가 원자적으로만 갱신한다(턴 처리의 행 갱신은 건드리지 않는다).
--   last_resumed_at   마지막으로 다시 열린 시각.
ALTER TABLE retrospects
    ADD COLUMN end_reason VARCHAR(30),
    ADD COLUMN last_user_turn_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN resume_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_resumed_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT chk_retrospects_end_reason CHECK (end_reason IS NULL OR end_reason IN (
        'WRAP_UP_ACCEPTED', 'NO_OTHER_MEMORY', 'USER_REQUEST', 'TURN_CAP', 'ABUSE_STOPPED',
        'LEGACY_EXPLORATION')),
    ADD CONSTRAINT chk_retrospects_resume_count CHECK (resume_count >= 0);

-- question_sent: 이 턴에 열린 탐색 질문이 실제로 나갔는가. 문장 생성이 실패해 고정 열린 질문(「조금 더 들려주실래요?」)으로
-- 대신한 턴도 true 이고 generation_failed 로 가른다. 다른 기억 고정 질문 · 마무리 제안 · 턴 상한 · 위기 안내로 끝난 턴은
-- false 다. 전략 대화가 아니거나 이 열이 생기기 전의 행은 NULL.
ALTER TABLE retrospect_turn_judgments
    ADD COLUMN question_sent BOOLEAN;
