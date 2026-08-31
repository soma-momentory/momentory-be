# 회고 AI 채팅 v2 재설계 계획 (권위본)

> 출처: `채팅흐름_v2` 스펙. 대화가 서버 주도이므로 이 문서(BE)가 전체 계획의 정본이고,
> FE 실행 계획은 `momentory-fe/docs/retro-chat-v2-plan.md` 에 있다.
> 브랜치: `feat/retro-chat-overhaul` (BE·FE 동일 이름).

## 0. 한 줄 요약

회고를 **초기 감정 선택 없이 → 일기 작성 채팅(≤6턴, 자유) → 선택 시 감정 탐색(고정 3턴) →
바람 카드** 구조로 바꾼다. Gemini 클라이언트·structured output·안전 가드·metering·pgvector
같은 밑단 인프라는 **그대로 살고**, 그 위의 대화 엔진(`Phase`/`RetroMode`/`Direction`/`Scripts`)을
재작성한다.

## 1. 확정된 결정

1. **바람 카드가 기존 행동 카드를 대체한다.** `action_cards` 인프라(테이블·pgvector 추천·완료
   기록·쉼터/일기상세 화면)를 바람 카드 형태로 개편해 재사용한다. "감정 탐색을 진행한 경우에만
   카드 생성"이라는 v2 규칙으로 바꾼다.
2. **대화 엔진 = 서버 규칙(슬롯) + LLM(질문 생성·추출).** 서버가 6턴·슬롯·종료를 통제하고 LLM은
   질문 문장과 슬롯 추출만 담당한다. **감정 추출은 `EmotionExtractor` 인터페이스 뒤에** 둬서,
   나중에 전용 감정 분류 모델로 공급자만 교체할 수 있게 이음매를 남긴다.
3. **욕구 리스트(단어:뜻)와 감정 풀을 고정 도메인 상수로 박는다.** LLM은 이 중에서 고르고,
   FE는 서버가 준 옵션만 렌더한다.
4. **1일 1회 제한·`safety_hold` 위기 플로우·answer-gate(짧은 답변 재질문)는 유지한다.**
5. 남겨둔 옛 브랜치(`refactor/retrospect-ai-chat`, `feat/chat-profanity-guardrail`)는 고려하지
   않는다. 안전 로직은 이미 현재 트리에 들어와 있다.

**event 다중 처리:** 하루에 사건이 여러 개(대개 ≤2)여도 상태·다운스트림은 **핵심 사건 하나로
수렴**시킨다. 감정 탐색·바람 카드·일기의 중심이 모두 단일 핵심 장면을 전제하기 때문. 곁가지로
언급된 사건은 `secondary_events[]` 에 담아 일기 본문에만 가볍게 반영한다(v2 예외 처리:
"그중 오늘 일기에 가장 남기고 싶은 건 어떤 일이야?"로 중심 선택).

## 2. 그대로 살리는 인프라 (재작성 아님)

- `GeminiApiClient` / `GeminiResponseSchemas` (structured JSON 출력)
- 안전 3종: `SafetyPolicy` · `AbuseGate` · `PromptLeakGuard`
- `AnswerGate` / `reask-cap` (짧은·회피 답변 재질문)
- `LlmUsageLogger` 등 metering
- `DiaryWriter`(G4) — 일기 생성 콜
- pgvector 상황 임베딩(`GeminiSituationEmbedder`, `SituationBasedRecommender`, HNSW 인덱스)
- `RetrospectState` 를 `state_json` 스냅샷으로 저장하는 방식(`RetrospectStateCodec`)
- 엔드포인트 골격(`POST /retrospect`, `POST /retrospect/{id}/messages`) — 필드만 확장

## 3. 상태 구조 (v2 → `RetrospectStateSnapshot` 재정의)

```
session_id
diary_chat {
  status: in_progress | completed | user_ended
  turn_count, max_turns = 6
  event:            string | null        // 핵심(중심) 사건 — 다운스트림이 참조하는 유일한 것
  secondary_events: string[]             // 곁가지 사건 (일기 본문에만; 감정탐색은 무시)
  emotions: [ { raw, normalized, timing, cause, evidence } ]   // 핵심 사건 기준, 복수
  meaning:          string | null
  diary:            string | null
}
emotion_exploration {
  entered: bool, status: not_started | in_progress | completed | user_ended
  turn_count, max_turns = 3
  confirmed_emotions: [...]   // 1턴 결과 (최대 2)
  needs:              [...]   // 2턴 결과 (고정 욕구 리스트에서, 최대 2)
  desired_state:      string | null   // 2턴에서 추출한 "바랐던 모습"
  small_action:       string | null   // 3턴 결과
}
outputs { wish_card: object | null, next_action: "view_diary" }
```

**저장 원칙:** `evidence` 에는 사용자가 실제로 말한 문장을 저장. 추정 감정은 후보로만 쓰고
`confirmed_emotions` 에는 넣지 않음. 감정 탐색 미진입이면 `entered:false`,`wish_card:null`.
행동 미정이면 `small_action:null` 허용.

## 4. Phase / 대화 흐름

`Phase` enum 재정의 (기존 `RetroMode`/`Direction`/`SubDirection` 축 폐기):

```
diary_chat ──(6턴/충분/그만)──▶ await_branch ──[일기 확인하러 갈래요]──▶ complete
                                     │
                                     └─[감정을 더 알아볼래요]─▶ emotion_exploration(3턴 고정) ─▶ complete
        (안전 신호) ──▶ safety_hold ⇄ resume        (반복 어뷰징) ──▶ ended
```

### 4.1 일기 작성 (`diary_chat`) — 한 턴 처리 (`RetrospectEngine`)

1. **answer-gate** — 비었거나 회피성이면 `reask-cap`(1) 만큼 되묻고 진행. 위기 신호면 `safety_hold` 이탈.
2. **추출 콜(LLM, structured)** — 입력: 직전 질문 + 이번 답변. 출력: 이 답변이 채운 슬롯
   `{ event?, secondary_events?, emotions[]?, meaning? }` + 각 `evidence`. 서버가 슬롯 갱신.
   - 사건이 여럿이고 명확한 중심이 있으면 자동 채택, 우열 불명(≈2개)일 때만 다음 턴에 중심 선택 질문.
   - **감정 필드는 `EmotionExtractor` 인터페이스를 통과**시킨다(지금 `LlmEmotionExtractor`, 나중 `ModelEmotionExtractor`).
3. **종료 판정(서버 규칙, 결정적):**
   - 사용자가 "됐어/그만할래" → 즉시 종료(확인된 것만)
   - `event ∧ (emotions ≥ 1) ∧ meaning` → 충분(2~4턴 조기종료 가능)
   - `turn_count ≥ 6` → 있는 것만으로 종료
4. 계속이면 **다음 질문 대상 슬롯 선택(서버 규칙)** — 빈 슬롯 우선순위(사건→구체화→감정→의미) 중 하나.
5. **질문 콜(LLM, structured `{question}`)** — 대상 슬롯 intent + 직전 답변(되비추기) + 개인화 소재.
   실패 시 슬롯별 스크립트 fallback 문안.

> **콜 구성:** 추출·질문을 **한 콜(G2식)로 합치되 출력 스키마에서 감정 블록을 독립**시켜
> `EmotionExtractor` 로 통과시킨다(비용 1콜, 이음매는 인터페이스로 확보).

종료 후: `DiaryWriter`(G4)로 일기 생성 → `diary` 채움 → `await_branch` 로 전이하며
"오늘 이야기는 일기로 정리해뒀어. 지금 느낀 감정을 조금 더 알아볼까?" + 옵션 2개 제시.
**채팅 중에는 일기 초안을 노출하지 않는다.**

### 4.2 감정 탐색 (`emotion_exploration`) — 고정 3턴

| 턴 | 목적 | 진행 | 슬롯 |
|---|---|---|---|
| 1 | 감정 확인 | `emotions[]` 후보 2~4개 제시 + `직접 적기`/`아직 잘 모르겠어요`, 최대 2 선택 | `confirmed_emotions` |
| 2 | 바람(욕구) 확인 | **고정 욕구 리스트**에서 맥락에 맞는 3~4개를 `단어 : 뜻` 형식으로 제시, 최대 2 선택 + `직접 적기`/`모르겠어요` | `needs`, `desired_state` |
| 3 | 작은 행동 | 후보 2~3개 제시 + `직접 적기` + `오늘은 여기까지 할래요` | `small_action` |

- 감정 성격(불편 vs 긍정)에 따라 2턴 워딩이 갈린다: 불편="그때 내 마음이 바랐던 것은?" /
  긍정="오늘 내 마음을 채워준 것은?". 행동을 정하지 않아도 실패로 처리하지 않는다.

## 5. 바람 카드 (행동 카드 대체)

**생성 조건:** `emotion_exploration.entered && completed` 일 때만. 일기만 하고 끝난 세션엔 만들지 않는다.

**카드 필드 (v2):** 상황(=핵심 event 요약) · 감정(confirmed, ≤2) · 내 마음이 바랐던 것(needs, ≤2) ·
바랐던 모습(desired_state) · 작은 행동(small_action) · sentiment(불편/긍정 → 워딩 분기).
사용자가 `모르겠어요`/`여기까지` 로 비운 항목은 임의로 채우지 않고 `아직 정하지 않음` 표시.

**`action_cards` 테이블 개편(마이그레이션):**
- 유지: `situation`, `target_action`(→ 작은 행동 의미로 유지), `done`/`done_at`/`reflection`(쉼터 완료),
  `situation_embedding`(pgvector 추천), `retrospect_id`, `from_rest_preference`
- 신설: `emotions`(enum 배열/json), `needs`(json: `[{word, meaning}]` 또는 코드), `desired_state`(text),
  `sentiment`(enum)
- 컬럼/테이블명은 **유지**하고(리포지토리·리스너·컨트롤러 파급 최소화) 도메인 개념·API·화면 라벨만
  "바람 카드"로. → §10 미결 1.

## 6. 감정 태그 (N:N)

일기에 **주요 키워드 1~2개 × 감정(normalized)** 을 N:N으로 매칭해 저장한다(v2 Phase 4). 현재
`diaries` 는 `current_emotion`/`schedule_emotion` 단일 enum이라 **다중 감정 저장 구조로 확장**해야
한다(json 배열 또는 조인 테이블). → §10 미결 3.

## 6.5 감정 taxonomy (확정 — v2대로 분리 + 긍정→부정 번호)

**9종 → 10종.** `화남·답답함`을 `화남`/`답답함`으로 분리한다(신설은 `답답함` 하나). `당황스러움`은 넣지
않는다. `normalized` 감정은 반드시 이 고정 집합의 하나로 매핑된다.

**id를 긍정→부정 순서로 다시 매긴다**(renumber). 기존 `EMOTIONS_BY_SENTIMENT` 의 긍정→부정
그라디언트를 번호로 삼고 끝에 `답답함`을 붙인다:

| id | 이름 | server key | sentiment |
|---|---|---|---|
| 1 | 행복함 | happy | 긍정 |
| 2 | 뿌듯함 | proud | 긍정 |
| 3 | 평온함 | calm | 긍정 |
| 4 | 피곤함 | tired | 불편 |
| 5 | 막막함 | stuck | 불편 |
| 6 | 무기력 | lethargic | 불편 |
| 7 | 불안함 | anxious | 불편 |
| 8 | 우울함 | depressed | 불편 |
| 9 | 화남 | angry | 불편 (라벨만 좁힘) |
| 10 | 답답함 | frustrated | 불편 (신설·화남에서 분리) |

- **sentiment 3긍정(id 1–3) / 7불편(id 4–10)** — 바람 카드 워딩 분기(불편="바랐던 것" /
  긍정="채워준 것")에 쓴다. id 순서 자체가 긍정→부정이라 `EMOTIONS_BY_SENTIMENT` 는 항등 순서로
  단순화(또는 제거)된다.
- ⚠ **번호는 FE 로컬 규약일 뿐, 저장/전송은 server key(string) 로 한다.** BE `Emotion` enum과 diary
  저장값은 key 문자열이라 renumber 영향 없음. FE는 `EMOTION_KEYS`(id↔key) 매핑만 새 번호로 맞추면 되고,
  숫자를 영속화하는 곳이 없어야 안전하다(현재 감정은 서버 key 로 오간다).
- **파급 파일(전부 함께 고친다):**
  - BE `retrospect/domain/Emotion.java` enum — `frustrated` 추가(angry 의미 축소)
  - BE `diaries.current_emotion`/`schedule_emotion` enum 값 + 마이그레이션(기존 angry=화남·답답함 데이터는 angry로 유지)
  - FE `src/features/emotion/model/emotion.ts` — `EMOTIONS`(10, 긍정→부정 순 재번호), `EmotionId`(1–10), `EMOTIONS_BY_SENTIMENT`(항등화/제거)
  - FE `src/features/retro/model/retro.ts` — `EMOTION_KEYS` 전 항목 새 번호로 재매핑(+10 frustrated)
  - FE `src/features/home/data/scheduleApi.ts` — `SCHEDULE_EMOTIONS`(ANGRY_FRUSTRATED 분리 → ANGRY/FRUSTRATED · id 재번호 반영)
  - 디자인 감정 색 — 신설 1종 색 필요(`src/design/ds/` · DESIGN.md)

## 7. 계약 변경 (엔드포인트·DTO)

- **`POST /api/v1/retrospect`** — `currentEmotion` **required 제거**(시작 시 감정 선택 없음).
  `schedules[].emotion` 도 선택. 시작은 스케줄 소재(또는 빈 "오늘 하루")만으로.
- **`ReplyDto`** — `phase` 값 집합 교체(`diary_chat`/`await_branch`/`emotion_exploration`/`complete`/
  `safety_hold`/`ended`). `options`·`ui`·`measures`·`diary`·`done`·`safetyLevel` 유지.
  감정/욕구/행동 후보는 기존 `options` 로 표현(직접입력=`input:true`).
- **바람 카드 DTO** — 기존 `actionCard` DTO를 바람 카드 형태로 확장(상황·감정·바람·바랐던 모습·행동).
  완료 turn의 결과로 실린다.
- OpenAPI 스냅샷(`docs/api`)·FE `sync:api` 갱신 필요.

## 8. 폐기 목록

- `RetroMode` · `Direction` · `SubDirection` · `Scripts` 의 mode 분기
- `SchedulePicker` (시작 감정 선택 소멸로 축소/제거)
- `currentEmotion` required 경로

## 9. 구현 순서 (BE 먼저)

1. **상태·계약** — `RetrospectStateSnapshot` 을 §3 구조로 재정의, `Phase` 재정의, `ReplyDto`/Start 요청
   조정, 마이그레이션 스켈레톤.
2. **일기 작성 엔진** — 슬롯(event/secondary/emotions/meaning) + 6턴 캡 + 조기종료 + 추출/질문 콜.
   `EmotionExtractor` 인터페이스 도입. `RetroMode`/`Direction` 제거.
3. **분기점 + 감정 탐색 3턴** — 고정 3턴 핸들러, 욕구·감정 **고정 리스트 상수**, 옵션/직접입력/모르겠어요.
4. **결과물** — 감정 태그 N:N + 바람 카드 생성(`action_cards` 개편) + 완료 이벤트로 diary/card 저장 재사용.
5. **프롬프트 튜닝** — `PromptFactory` 를 v2 Phase별 시스템 프롬프트로(스펙 8장 참고).
6. **테스트·E2E** — 종료 규칙·슬롯·3턴·바람 카드 회귀.

FE 병행(§FE 계획): 3~4단계 계약이 서면 FE 화면 착수.

## 10. 미결 / 리스크

1. **테이블 rename 여부** — `action_cards` 명 유지(추천, 파급 최소) vs `wish_cards` 로 rename(개념 정합).
2. **쉼터 "해봤어요" 완료** — 바람 카드에도 완료/느낀점을 유지할지(v2 미언급, 쉼터가 의존 → 유지 추천).
3. **`diaries` 다중 감정 저장** — json 배열 vs 조인 테이블.
4. ~~감정 taxonomy 불일치~~ → **해결: §6.5 대로 9→10종(화남·답답함 분리, 당황스러움 제외, 긍정→부정 재번호)으로 확정.**

## 11. AGENTS.md 준수 (구현 시 반드시)

구현 착수 전 `momentory-be/AGENTS.md` 를 다시 확인한다. 이 계획에 특히 걸리는 조항:

- **계약 변경은 승인 필요** — §7의 `currentEmotion` required 제거·`ReplyDto.phase` 값 교체·바람 카드 DTO는
  API 계약 변경이다. 사용자 승인(=본 v2 재설계)에 근거해 진행하되, **엔드포인트 경로·HTTP 메서드·에러 코드는
  유지**하고 필드만 바꾼다. 에러 응답 `{code,message}` 계약 보존.
- **OpenAPI** — 엔드포인트/DTO 변경 시 **OpenAPI 계약 테스트와 `/v3/api-docs` 를 함께 갱신·검증**. 모든 비-2xx는
  실제 코드와 일치하는 `ExampleObject` 유지.
- **Flyway** — 적용된 마이그레이션은 수정 금지, 새 파일 추가만. **감정 2종 추가는 `EnumType.STRING`(varchar)이라
  DB 마이그레이션 불필요**(앱 enum만). 마이그레이션이 필요한 것은 ⑴ `action_cards` 바람 카드 필드(§5),
  ⑵ `diaries` 다중 감정 태그 구조(§6). 기존 데이터·NOT NULL 고려.
- ⚠ **`diaries.current_emotion` 이 `nullable=false`** — 시작 감정 선택이 사라지므로, 확정 감정을 추출 결과에서
  채우거나(권장: normalized 주 감정 1개) 컬럼 정책을 재검토해야 한다. 다중 태그는 별도 구조로.
- **인터페이스 최소화** — AGENTS.md는 근거 없는 port/interface 도입을 지양한다. `EmotionExtractor` 는 "향후 감정
  분류 모델 교체"라는 **구체적 근거**가 있으므로 도입하되, 그 하나로 최소화(전략·팩토리 남발 금지).
- **트랜잭션·이벤트** — 완료 시 diary/card 저장은 현행 `RetrospectCompleted` 동기 이벤트 경로를 유지(한 트랜잭션).
- **검증** — 비자명 변경이므로 `momentory-backend-delivery` 흐름을 따르고 `./gradlew clean check` 를 실제 실행.
  실행 안 한 검증을 성공으로 보고하지 않는다.
- **테스트** — 종료 규칙·슬롯·3턴·바람 카드에 통합/도메인 테스트 추가. HTTP 상태만 단언 금지, DB 상태·롤백까지.
