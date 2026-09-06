# 회고 채팅 v2 — 진행상황 & 이어서 할 일 (핸드오프)

> 마지막 작업: 2026-08-28. 브랜치 `feat/retro-chat-overhaul`(BE·FE 동일).
> 계획 정본: [`retro-chat-v2-plan.md`](retro-chat-v2-plan.md) · FE 계획: `momentory-fe/docs/retro-chat-v2-plan.md`.
>
> **다음에 시작할 것 → FE(task 6): `momentory-fe/docs/retro-chat-v2-plan.md`.** BE 핵심(tasks 1~5)은 완료.
> BE 남은 것은 전부 Docker/키 환경 필요(통합테스트 v2 재작성 · OpenAPI 계약 · v1 죽은코드 정리).

## TL;DR

v2 대화 엔진(일기작성 슬롯 → 분기점 → 감정탐색 3턴 → 종료) + **바람카드/일기 감정태그 영속화(쓰기·읽기)**
+ **실제 Gemini 어댑터·v2 프롬프트**까지 **BE 완성**. `./gradlew clean check` **BUILD SUCCESSFUL**
(단위 green, 통합은 Docker 없어 스킵). 남은 건 전부 **Docker/키 환경**에서 할 것: 통합테스트 v2 재작성,
OpenAPI 계약 갱신, v1 죽은코드 정리. **다음 주요 작업은 FE.**

## 검증 방법 (이어서 할 때 먼저 확인)

```bash
cd momentory-be
./gradlew --no-daemon clean check      # 현재 green (단위 테스트만 실행; 통합은 Docker 없으면 스킵)
```
⚠ 이 개발 환경엔 **Docker가 없어** Testcontainers 통합 테스트가 전부 스킵된다. 통합 테스트·런타임
컨텍스트 로드·실제 Gemini 호출 검증은 **Docker + Gemini API 키가 있는 환경**에서 해야 한다.
`--no-daemon` 을 쓰면 백그라운드 Gradle 데몬이 안 남는다.

## ✅ 완료 (green)

### 감정 taxonomy 9→10종 (긍정→부정 재번호는 FE 몫)
- `Emotion.java` — `FRUSTRATED`(답답함) 추가, `ANGRY` 라벨 "화남"으로 좁힘, `fromLabel()` 추가.
  BE enum 선언 순서는 유지(저장은 string key). ⚠ **`is-` 접두 메서드 금지** — record 컴포넌트와
  Jackson 프로퍼티 충돌(그래서 `hasNormalized()`). 
- `report/domain/WeeklyMood.java` — `FRUSTRATED` 분기 추가.

### v2 도메인 모델 (신규)
- `Phase` — `DIARY_CHAT`·`AWAIT_BRANCH`·`EMOTION_EXPLORATION` 추가(+ 레거시 phase는 아직 남아 있음, 정리 대상).
- `ExtractedEmotion`(raw·normalized·timing·cause·evidence), `Need`+`Needs`(욕구 50종 단어:뜻 고정),
  `WishCard`+`WishSentiment`, `Choice`(선택지).
- assistant 포트: `DiaryChatAssistant`(+`DiaryTurn`), `EmotionExtractor`(감정 이음매 — 향후 감정
  분류 모델로 교체 지점), `ExplorationAssistant`. `DiaryWriter`는 재사용.

### 상태·엔진·계약
- `RetrospectState`/`RetrospectStateSnapshot` — v2 슬롯 구조로 재작성(사건·곁가지·감정·의미 + 탐색 슬롯).
- `RetrospectEngine` — 전면 재작성. 서버가 6턴·슬롯·종료 통제, LLM은 질문·추출·후보만. 감정 추출은
  **일기작성 끝에 대화 전체로 1회**(`EmotionExtractor`). 안전(safety_hold/abuse)·게이트 재사용.
- `RetrospectService` — mode/currentEmotion 제거 반영. `Retrospect` 엔티티 — `mode`·`current_emotion` 컬럼 제거.
- 계약: `StartCommand`/`StartRetrospectRequest`(currentEmotion 제거), `TurnCommand`/`TurnRequest`
  (다중선택 `optionIds`, 슬라이더 `measures` 제거).
- 마이그레이션 `V19__drop_retrospect_mode_and_current_emotion.sql`.

### AI 어댑터 (임시)
- 옛 어댑터 4개 삭제(`PromptFactory`·`GeminiTurnScripter`·`GeminiUnderstandingChecker`·`GeminiDiaryWriter`).
- **무-AI 폴백** 4개 투입(`Fallback*`): turn empty / 감정 [] / 후보 [] / 일기 empty → 엔진이
  폴백 질문·고정 욕구 앞자리·최소 일기로 **결정적으로 동작**(테스트용으로 좋음).

### 테스트
- `RetrospectEngineTest`·`RetrospectStateTest`·`RetrospectStateCodecTest` v2로 재작성(단위, 통과).
  `FakeAssistant`는 새 4포트 페이크.
- `PromptFactoryTest` 삭제. 5개 통합 테스트의 시드 헬퍼(`Retrospect.start`) 새 시그니처로 컴파일 수정.

## ✅ 완료 A — ④ 바람카드/다중 감정 영속화 (2026-08-28 추가, green)

- **바람카드 리치 영속화**: `ReplyDto.WishCardDto`(상황·감정키[]·바람[{단어,뜻}]·바랐던모습·작은행동·성격),
  `RetrospectEngine.buildWishCard()`, `RetrospectCompleted.WishCardData`, `ActionCard.createWish()` +
  컬럼(`emotions`·`needs` CSV, `desired_state`, `sentiment`) + 마이그레이션 **V20**(+ `target_action` NOT NULL 완화).
  `ActionCardFromRetrospectListener` 가 저장. 감정 탐색을 거치면 작은 행동이 없어도 카드 생성(빈칸 허용).
- **일기 감정 태그(N:N)**: `diaries.emotions` CSV 컬럼 + `current_emotion` **nullable**(마이그레이션 **V21**),
  `Diary.create(..., emotions)`, `DiaryFromRetrospectListener`, `RetrospectCompleted.DiaryData.emotions`.
- **잠재 버그 수정**: 감정 없이 끝난 일기가 `current_emotion` NOT NULL 때문에 완료를 롤백시키던 문제 해소.
- 감정·바람은 **키/단어 CSV**로 저장하고 뜻·라벨은 읽는 쪽이 `Emotion`/`Needs` 에서 역참조.
- 엔진 단위 테스트로 바람카드 경로 검증(감정 탐색 3턴 → wishCard, '여기까지' → 카드 있고 smallAction만 null).

### A의 남은 것 (읽기측 노출 — 다음)
- `ActionCardView`/`ActionCardResponse`, `DiaryView`/`DiaryResponse` 에 새 필드(감정·바람·바랐던모습·성격,
  일기 감정태그) 노출 → 쉼터(R2)·일기상세(A3) 가 바람카드를 표시. **OpenAPI 계약 갱신** 동반.

## ✅ 완료 B — ③ 실제 Gemini 어댑터 + v2 프롬프트 (2026-08-28 추가, green)

- `PromptFactory`(v2 시스템 프롬프트 + 역할별: diaryTurn/emotionExtract/needs/actions/diary), Gemini 어댑터
  4종(`GeminiDiaryChatAssistant`·`GeminiEmotionExtractor`·`GeminiExplorationAssistant`·`GeminiDiaryWriter`),
  응답 스키마(`GeminiResponseSchemas` v2 타입들 + `GeminiStructuredOutputs` DTO). 폴백 어댑터 4종 제거.
- 감정은 고정 10종 키로만 정규화(모르는 키 버림), 바람은 고정 `Needs` 로 검증(환각 버림).
- ⚠ **실제 호출 검증은 키/Docker 필요** — 테스트 env는 더미 키라 호출이 조용히 실패→엔진 폴백(결정적).
  `GeminiApiClientTest` 는 `DiaryOutput` 기준으로 갱신됨.

## ⚠ 임시 매핑 / 기술 부채 (이어서 갚아야 함)

1. **`current_emotion` 은 대표 감정 1개로 축약 저장**(리포트용) — 태그는 `emotions` CSV 에 전체가 있다.
2. **v1 죽은 코드가 남음**(컴파일만 됨): `domain/script/*`·`SchedulePicker`·assistant `TurnScripter`/
   `UnderstandingChecker`+`TurnScript`/`UnderstandingCheck`·`ScriptsTest`·`SchedulePickerTest`.
   지우면 통합테스트 7개의 잔재 import(`RetroMode` 등)를 함께 고쳐야 해서, **통합테스트 v2 재작성 패스와
   함께 정리**(Docker 환경).
3. **PriorActionCard/pgvector 추천 배선은 남아 있으나 엔진이 안 부름** — v2에서 "비슷한 상황 지난 행동"
   되살리기를 다시 붙일 자리(미래).
5. **레거시 v1 코드가 아직 남음**(컴파일만 됨, 죽은 코드): `domain/script/*`(Scripts·RetroMode·Direction·
   SubDirection·ScriptStep·MeasureField·OptionItem), `SchedulePicker`, assistant `TurnScripter`/
   `UnderstandingChecker`+`TurnScript`/`UnderstandingCheck`, `GeminiResponseSchemas`(이 record들 참조),
   `PriorActionCard*`. 관련 테스트 `ScriptsTest`·`SchedulePickerTest`도 남음. → 나중에 정리.
6. `retrospects` 테이블에 옛 `diary`·`reframed_diary` 컬럼 잔존(내 변경 아님, V13에서 diaries로 이동한 잔재).

## 다음 작업 (순서)

### A. ④ 바람카드/다중 감정 영속화 — ✅ 완료 (위 §완료 A 참조)
남은 것: **읽기측 노출**(ActionCardResponse/DiaryResponse + OpenAPI) — 쉼터·일기상세 표시. → D와 함께 하면 좋음.

### B. ③ 실제 Gemini 어댑터 + 프롬프트 (Docker/키 필요) ← **다음 시작 후보 1**
- `Fallback*` 3개를 실제 Gemini 어댑터로 교체(`GeminiDiaryChatAssistant`·`GeminiEmotionExtractor`·
  `GeminiExplorationAssistant`) + 새 `DiaryWriter` 어댑터. `GeminiResponseSchemas` 에 새 structured 스키마.
  v2 시스템 프롬프트(스펙 8장) 반영. 감정 정규화는 고정 10종으로 매핑.
- 폴백과 실제 어댑터 빈 충돌 주의(@Primary/@ConditionalOnMissingBean 또는 폴백 제거).

### C. 통합 테스트 v2 재작성 (Docker 필요)
- `RetrospectApiIntegrationTest`·`RetrospectActionCardPersistenceIntegrationTest` — **아직 v1 흐름 단언**.
  시드 헬퍼만 컴파일 수정된 상태라 Docker에서 돌리면 실패한다. 새 계약(start=currentEmotion 없음,
  turn=optionIds, phase=diary_chat/…)으로 전면 재작성.

### D. OpenAPI 계약 갱신 + 계약 테스트
- start/turn 요청·`ReplyDto`·phase 값 바뀜. `docs/api` 스냅샷 + FE `sync:api`. (AGENTS.md OpenAPI 게이트)

### E. v1 죽은 코드 정리 (위 부채 5)

### F. FE (task 6 · `momentory-fe/docs/retro-chat-v2-plan.md`)
- 감정 taxonomy 10종 긍정→부정 재번호, `emotion.tsx`(C3) 제거·`confirm→talk` 직결, `talk` 새 phase 렌더,
  `WishCardSection`(쉼터·A3·done), 다중 감정 태그. FE는 이 환경에서 돌려볼 수 있음.

## 참고

- 결정 6개(바람카드=대체 · 대화엔진=슬롯+LLM+감정이음매 · 욕구·감정 고정 · 1일1회/safety/answer-gate 유지 ·
  옛 브랜치 무시 · 감정 10종 긍정→부정 재번호)는 `retro-chat-v2-plan.md` 참조.
- 구현 착수 전 항상 `AGENTS.md` 확인(계약 변경 승인·Flyway 새 파일·EnumType.STRING·OpenAPI 계약 테스트·
  `momentory-backend-delivery` 흐름·`./gradlew clean check`).
