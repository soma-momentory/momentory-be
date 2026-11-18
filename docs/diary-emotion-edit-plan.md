# 사건별 감정 수정 — 구현·검증

2026-11-18, FE·BE 브랜치 `feat/diary-emotion-edit`. API 확장과 구현은 사용자 승인 범위다. 각 검증 절은 해당 시점의 결과를 기록하며, 최종 기준과 기능별 커밋은 마지막 절을 따른다.

## 동작과 저장

채팅 직후 `retro/draft`와 보관함 `diary/[id]`에서 같은 편집기를 사용한다. 조약돌은 `사건 - 기쁨` 형식으로 연결된 사건을 모두 표시한다. 사건별 복수 감정과 사건 없는 복수 감정을 저장·해제할 수 있으며 전체 해제도 허용한다. 취소·실패 때 서버에 저장된 기존 감정을 유지하고, 실패한 편집 초안은 다시 저장할 수 있다. 긴 사건명은 줄바꿈하고 시트 내용은 화면 안전 영역 안에서 스크롤한다.

원본 회고 상태는 바꾸지 않는다. 감정 탐색에서 확정한 감정은 당시 선택했던 사건에 연결하며, 옛 세션은 기존 대표 사건 복원 규칙을 따른다. 사건 정보가 없는 옛 기록은 감정만 표시한다. 목록의 옛 회고는 일괄 조회한다.

직접 작성 본문을 수정하면 새 사건을 사용한다. 현재 감정 태그를 유지하고 새 사건에 근거가 없는 감정은 사건 없는 감정으로 보존한다. 본문 수정 응답의 사건·감정을 같은 화면과 store에 반영한다. 감정 조회·저장 응답이 편집한 본문을 덮거나 세션 변경 후 다른 사용자의 store를 갱신하지 않도록 보호한다.

## API

`PUT /api/v1/diaries/{id}/emotions`

```json
{
  "events": [
    { "eventId": 1, "emotions": ["joy", "calm"] },
    { "eventId": 2, "emotions": [] }
  ],
  "unlinkedEmotions": ["sad"]
}
```

모든 사건 번호를 빠짐없이 한 번씩 보내며, 사건명·요약은 서버 원본을 사용한다. 현재 12종 감정 키만 허용한다. 인증 주체로 소유권을 확인한다. 성공 200, 잘못된 사건/감정/형태 400 `INVALID_REQUEST`, 인증 없음 401 `AUTHENTICATION_REQUIRED`, 일기 없음/소유권 없음 404 `DIARY_NOT_FOUND`다. 실제 오류 스키마·메시지·예시는 OpenAPI 계약 테스트로 검증했다.

일기 목록·단건·수정 응답은 기존 필드와 다음 필드를 함께 제공한다.

- `events`: `eventId`, `label`, `summary`, `emotions`의 목록.
- `unlinkedEmotions`: 사건 없는 감정 키 목록.
- `emotionClassificationChanged`: 현재 감정에 최초 채팅 감정이 하나라도 빠져 있는지 여부. 복원하면 false.

프론트 OpenAPI 스냅샷은 실제 `/v3/api-docs` 계약 테스트 산출물에서 테스트 전용 `/test-auth/**`를 제외하고, 로컬 HTTP와 공식 `sync:api` 스크립트로 반입했다. 이번 API 외에 기존 BE 코드의 회고 재개·옷장·삭제된 일기 날씨 경로 드리프트도 스냅샷에 반영됐다.

## 최초 분류 변경 지표

비교 기준은 사건별 연결을 합친 최초 감정 집합과 현재 감정 집합이다.

| 현재 저장 내용 | 변경 여부 |
| --- | --- |
| 최초 감정에 추가만 함 | false |
| 사용자가 추가했던 감정만 제거 | false |
| 최초 감정 중 하나라도 일기 전체에서 제거 | true |
| 같은 감정이 다른 사건이나 사건 없는 감정에 남음 | false |
| 최초 사건 없는 감정이 일기 전체에서 제거됨 | true |
| 취소·저장 실패 | 이전 저장 상태 유지 |
| 최초 감정을 모두 복원 | false로 돌아감 |
| 직접 쓴 일기의 감정 수정 | false, 채팅 분류 지표 제외 |

현재 저장 구조는 nullable TEXT `original_emotion_selection_json`, `emotion_selection_json`과 NOT NULL BOOLEAN `emotion_classification_changed`다. V52 초기 구현을 적용한 DB도 V53에서 기존 변경 이력을 boolean으로 보존하고 전용 변경 시각 컬럼을 제거한다. 최초 수정 전 원본을 한 번만 스냅샷으로 남긴다. CHECK 제약은 변경 여부가 true이면 채팅 연결과 두 스냅샷이 동반되도록 한다. 기존 PK/FK·삭제 정책은 바꾸지 않는다. 일기를 삭제하면 같은 행의 이력도 삭제된다. 본문·감정 수정은 같은 일기 행의 잠금을 사용하며 스냅샷·감정 태그·대표 감정·변경 여부를 한 트랜잭션에서 반영한다.

현재 남아 있는 채팅 일기의 집계 예:

```sql
SELECT count(*) AS chat_diaries,
       count(*) FILTER (WHERE emotion_classification_changed = TRUE) AS corrected_diaries
FROM diaries
WHERE retrospect_id IS NOT NULL;
```

이력은 이 기능 배포 뒤 사용자 저장부터 수집된다. 사건명·감정 값·본문은 Amplitude/Firebase에 전송하지 않는다.

## 변경 파일

BE 신규:

- `application/DiaryEmotionService.java`, `application/InvalidDiaryEmotionsException.java`
- `domain/DiaryEmotionSelection.java`, `presentation/DiaryEmotionsRequest.java`
- `db/migration/V52__add_diary_emotion_selection.sql`
- `test/.../diary/domain/DiaryEmotionSelectionTest.java`, 이 문서

BE 수정:

- `diary/application/{DiaryQueryService,DiaryService,DiaryView}.java`
- `diary/domain/Diary.java`, `diary/infrastructure/{DiaryEventCodec,DiaryRepository}.java`
- `diary/presentation/{DiaryController,DiaryExceptionHandler,DiaryResponse}.java`
- `retrospect/infrastructure/persistence/RetrospectStateCodec.java`
- `test/.../diary/presentation/DiaryApiIntegrationTest.java`, `test/.../config/OpenApiContractIntegrationTest.java`

FE 신규:

- `src/components/diary/DiaryEmotionEditor.tsx`와 해당 테스트
- `src/features/diary/model/{diaryEmotions,useDiaryEmotions}.ts`, `model/__tests__/diaryEmotionStore.test.ts`
- `app/diary/__tests__/emotionEditing.test.tsx`

FE 수정:

- `app/diary/[id]/index.tsx`, `app/retro/draft.tsx`, `app/retro/__tests__/diary.test.tsx`
- `src/components/diary/DiaryPaper.tsx`와 해당 테스트, `src/components/ds/BottomSheet.tsx`
- `src/features/diary/data/diaryApi.ts`와 해당 테스트, `model/{diary,types}.ts`
- `NAV.md`, `docs/{DESIGN,PRODUCT,TASKS,ANALYTICS,DATA-SECURITY}.md`, `docs/api/{README.md,openapi.json}`

삭제 파일 없음. 기존 Firebase 관련 미커밋 변경은 보존했다.

## 검증·리뷰

- 프론트 `npm run typecheck`, `npm run lint`, `npm run typecheck:expo-lab` 통과.
- 프론트 관련 집중 테스트 통과 후 `npm test -- --runInBand`: 163개 스위트·2,134개 테스트 통과.
- 서버 집중 테스트: `./gradlew test --tests '*DiaryApiIntegrationTest' --tests '*OpenApiContractIntegrationTest' --tests '*DiaryEmotionSelectionTest'`.
- 서버 전체 필수 검증: `./gradlew clean check`.
- 두 저장소 `git status --short`, 추적 diff와 신규 파일, `git diff --check` 확인.
- 원본 보존·단순 추가 제외·사용자 추가 감정 제거 제외·최초 제거 시각 유지·동시 수정·권한·잘못된 입력·전체 해제·직접 작성·롤백·본문 사건 교체·같은 감정의 사건 연결/비연결 공존·확정 감정 사건 연결·두 상세 화면·세션 변경 응답·저장 실패를 검증했다.
- 별도 리뷰 에이전트로 구현 리뷰와 수정 재검토 3회 수행했다(준비 단계 리뷰 2회와 별도). 긴 사건 버튼 넘침과 직접 작성 본문 변경 후 낡은 사건 표시를 수정하고 회귀 테스트로 확인했다. 최종 리뷰에서 미해결 Critical/Major 없음. 기각한 발견 없음.

실기기 큰 글씨·시트 스크롤 배치와 실제 배포 환경은 미검증이다. BE V52/API 배포 뒤 프론트를 배포해야 한다. 자동 검사와 로컬 PostgreSQL 검증을 기기 검증으로 간주하지 않는다. 최종 검증 결과와 판정은 아래에 기록한다.


### 최종 판정: PASS WITH WARNINGS

마지막 확정 감정 연결 보완 뒤 집중 검증과 `./gradlew clean check`를 재실행해 성공했다. 최종 서버 결과: 125개 스위트, 1,123개 테스트, 실패·오류 0, 건너뜀 32. API/OpenAPI, Flyway/JPA, 소유권, 원본·동시성·롤백 게이트를 통과했다. 프론트 타입·전체 린트·Lab 타입·변경 파일 서식 검사와 163개 스위트·2,134개 테스트도 통과했다. 두 저장소 diff 검사 통과. 마지막 독립 리뷰에 미해결 Critical/Major 없음.

남은 경고는 네이티브 기기와 실제 배포 환경 미검증이다. 코드와 자동 검증은 커밋·PR 검토 가능한 상태이며, 운영 적용과 머지 판단은 기기 확인 및 서버 선행 배포 계획을 포함해야 한다. 커밋·PR 생성·푸시·머지는 수행하지 않았다.


## 2026-11-18 후속 수정: 편집 UI와 주요 사건

후속 요청에 따라 보관함 일기 상세의 상단 연필을 누른 편집 모드에서 사진 아래 `감정 수정하기`를 표시한다. 채팅 직후 화면도 같은 편집기를 사용한다. `직접 수정한 감정이에요` 문구는 없앴으며 원본 분류 변경 이력·최초 변경 시각은 계속 저장한다. 사건 없는 감정을 선택하면 해당 사건 선택지에 선택한 감정 이름을 즉시 표시하고, 다른 사건을 오가거나 저장한 뒤 다시 열어도 유지한다. 빠른 복수 선택은 최신 편집 상태를 기준으로 갱신한다.

감정 선택 카드는 일기 상세와 같은 종이색 배경에 테두리 없이 표시한다. 사건은 한 줄에 하나씩, 선택하면 진한 갈색·흰 글씨다. 감정은 대표 이름만 작은 원형 초록 면(테두리도 같은 초록)으로 표시하고 세부 감정은 작은 글씨로 바깥에 둔다. 미선택 선지는 기존 흰 배경·중립색 테두리를 유지한다. 감정 카드 안에서 독립 스크롤하며 다중 선택 안내와 선택 사건의 중복 설명은 제거했다. 감정 스티커는 처음 6개를 기존 사진 위쪽, 7번째부터 사진 아래쪽에 붙인다. iPhone 17 Pro 에뮬레이터에서 12개가 위·아래 각 6개로 표시되고 본문·수정 버튼과 겹치지 않는 것을 실제 화면으로 확인했다. 작은 폭·큰 글씨의 네이티브 검증은 별도로 남는다.

주요 사건은 최대 2개이며 **감정이 드러난 사건 → 근거 사용자 턴 수가 많은 사건 → 먼저 언급한 사건** 순으로 선정한다. 턴 수는 추출된 사건의 중복 없는 근거 발화 수다. 선정 후 본문은 기존 대화 순서로 이어 간다. 제외된 사건의 감정은 버리지 않고 사건 없는 감정으로 남기는 기존 규칙을 유지한다.

G4에는 선정 사건 목록과 관련 사용자 발화만 전달한다. 출력 문단의 내부 `eventId`와 사건 이름을 함께 검증한 다음 같은 사건 문단을 합쳐 최대 2개로 제한한다. 제외된 사건이 첫 문단에 있어도 제거하고, ID가 없거나 선정 사건이 빠지면 선정 사건 요약으로 안전하게 대체한다. 한 사용자 발화에 여러 사건이 섞인 경계도 사건 ID 검증으로 구분한다. 최초 생성·짧은 초안 재작성 모두 같은 검증을 적용한다. HTTP API·DB·마이그레이션 변경은 추가하지 않았다.

이 후속 작업에서 추가 수정한 BE 파일:

- `retrospect/infrastructure/ai/{GeminiEmotionExtractor,PromptFactory,DiaryDraft,GeminiResponseSchemas,GeminiDiaryWriter}.java`
- `retrospect/application/RetrospectEngine.java`
- `test/.../retrospect/infrastructure/ai/{GeminiEmotionExtractorTest,PromptFactoryTest,GeminiDiaryWriterTest}.java`
- `test/.../retrospect/application/RetrospectEngineTest.java`

새 파일·삭제 파일 없음. FE 상세 변경 파일은 앞의 목록과 `src/features/retro/ui/RetroStickerChoices.tsx`, `src/features/emotion/model/emotion.ts`, `src/design/ds/diaryPaper.ts`, 관련 디자인·작업 문서다.

후속 검증: FE `npm run typecheck`, 변경 파일 `npx eslint`, 관련 Jest 5개 스위트·99개 테스트, `git diff --check` 통과. BE 집중 검증 `./gradlew test --tests '*GeminiEmotionExtractorTest' --tests '*GeminiDiaryWriterTest' --tests '*PromptFactoryTest' --tests '*RetrospectEngineTest'` 통과. 독립 리뷰 3회에서 문단 수 제한 전에 사건을 검증하지 않던 Major와 ID 없는 같은 이름 사건의 복제 Minor를 확인·수정하고 회귀 테스트를 추가했다. 최종 리뷰에서 미해결 Critical/Major/Minor 없음, 기각 사항 없음.

중간 전체 검증 한 번은 집중 테스트와 동시에 실행해 XML 결과 파일을 쓰지 못하면서 실패했다. 이후 검증은 순차로 실행한다. 실제 유료 LLM 호출·운영 배포 검증은 실행하지 않았다. 모델의 실제 분류·문장 품질은 운영과 별개의 평가가 필요하다.


### 후속 최종 판정: PASS WITH WARNINGS

최종 코드로 다른 테스트와 겹치지 않게 `./gradlew clean check`를 실행해 **성공**했다: 125개 스위트·1,135개 테스트, 실패·오류 0, 건너뜀 32. 집중 테스트, 최종 전체 검증, 양쪽 저장소 `git diff --check` 통과. 기존 API/OpenAPI·권한·Flyway/JPA 회귀 검사도 이 전체 검증에 포함된다. 후속 기능에는 추가 HTTP 계약·저장 구조 변경이 없어 새 API 승인은 필요하지 않았다.

독립 리뷰 3회 완료, 미해결 Critical/Major/Minor 없음. 실제 유료 LLM·운영 환경과 작은 폭·큰 글씨의 네이티브 레이아웃은 미검증이므로 경고를 남긴다. 코드는 커밋·PR 검토 가능한 상태다. 커밋·PR 생성·푸시·머지는 수행하지 않았다.

로컬 8080 서버를 최종 코드로 재시작하고 개발 인증 `/api/v1/users/me` HTTP 200을 확인했다. 에뮬레이터는 로컬 API를 쓰는 Metro 8098에 연결되어 있다. 새 사건 선정·본문 제한은 앞으로 생성하는 일기부터 적용하며 기존 저장 일기를 재생성하거나 덮어쓰지 않았다.


## 2026-11-18 한 줄 일기 회귀 수정

사용자가 새 일기가 사건 요약 한 줄로만 나오는 사례를 보고했다. 로컬 저장 상태를 내용 출력 없이 검사한 결과 사건 1개에 사용자 근거 발화 4개가 있었고, `diaryDraft`는 사건 요약과 정확히 같았다. G4 성공 사용량 기록도 확인했다. 원본 모델 JSON은 저장하지 않으므로 어떤 필드가 불일치했는지는 확정할 수 없다. 기존 `forEvents`의 정확한 사건 이름·ID 동시 일치 및 검증 실패 즉시 요약 대체가 이 증상을 만드는 경로였다. 앞선 자동 검증만으로 실제 생성 품질까지 검증됐다고 볼 수 없었다.

수정 사항:

- `DiaryDraft.forEvents`: 사건 식별은 안정적인 원본 ID로 한다. 이름 표현 차이로 유효한 문장을 제거하지 않는다. 잘못된/누락된 ID, 선정 사건 누락은 계속 거부하며 최대 두 사건을 유지한다.
- `GeminiDiaryWriter`: 구조화 응답은 받았으나 사건 연결 검증에 실패하면 원본 사건 번호를 지정해 최대 1회·10초 한도로 복구한다. 복구 뒤 추가 재작성은 하지 않는다. 실패 로그에는 세션 ID와 사건 수만 남기며 본문·사건명은 기록하지 않는다.
- `GeminiResponseSchemas`: 내부 출력 사건 ID는 원래 번호이며 다시 매기지 않는다는 설명을 추가했다.
- `GeminiDiaryWriterTest`: 사건 이름을 풀어 쓴 정상 응답 보존, 잘못된 재번호 지정 후 복구, 실패 호출 수 경계를 검증한다.
- `GeminiApiClientTest`: 실제 구조화 JSON → MockWebServer → GeminiApiClient → GeminiDiaryWriter 경로에서 이름 표현이 달라도 5문장 본문을 보존함을 확인한다. 유료 호출은 없다.

신규/삭제 파일 없음. HTTP API·DB·인증·마이그레이션 계약은 변경하지 않았다. 관련 집중 검증 `./gradlew test --tests '*GeminiDiaryWriterTest' --tests '*GeminiApiClientTest' --tests '*PromptFactoryTest' --tests '*RetrospectEngineTest'`와 `git diff --check` 통과. 별도 리뷰 에이전트 1회 검토 완료, 미해결 Critical/Major/Minor와 기각 사항 없음. 원본 G4 JSON 및 실제 유료 모델 재현은 Unable to verify다.

같은 후속 요청으로 FE `app/retro/draft.tsx`는 보관함처럼 본문 편집 모드에서만 감정 수정 버튼을 표시한다. `직접 고치기` → 감정 편집 열기·저장·취소 → `수정 완료`의 통합 검증을 갱신했다. 감정 편집을 열면 키보드를 닫는다. FE 관련 2개 스위트·40개 테스트, `npm run typecheck`, 변경 파일 ESLint 통과. 디자인·작업 문서를 함께 갱신했다.


후속 회귀 수정 최종 판정은 **PASS WITH WARNINGS**다. 최종 `./gradlew clean check` 성공: 125개 스위트·1,138개 테스트, 실패·오류 0, 건너뜀 32. 양쪽 diff 검사와 FE 집중 검증 통과. 별도 리뷰 1회, 미해결 Critical/Major/Minor 없음. 원본 G4 JSON 부재로 당시 정확한 불일치 필드 및 실제 유료 모델 재현은 미확인이다. 새 코드와 자동 검증은 커밋·PR 검토 가능한 상태이며 커밋·푸시·머지는 수행하지 않았다. 기존에 생성된 한 줄 일기나 대화는 덮어쓰지 않았다.


## 2026-11-18 변경 여부만 저장

사용자 요청으로 변경 시각을 제거하고 `diaries.emotion_classification_changed` boolean만 보관한다. 최초 감정이 제거된 저장이면 true가 되고, 이후 복원·중복 요청에도 true를 유지하는 일기별 이력이다. 추가만 하거나 사용자 추가분만 제거한 저장은 false를 유지한다. 원본과 현재 감정 스냅샷은 그대로 둔다.

이미 적용한 V52은 수정하지 않고 V53을 추가했다. V52 시각이 있던 행은 true로 옮긴 다음 시각 컬럼을 제거한다. 새 컬럼은 NOT NULL DEFAULT FALSE이며 true에는 채팅 연결·원본·현재 스냅샷이 필요하다는 CHECK 제약을 유지한다. PK/FK·삭제 정책은 변경하지 않았다. 사용자 요청은 응답의 변경 시각 제거를 포함하는 것으로 적용했다. API 경로·메서드·오류 계약은 유지하고 `emotionClassificationChanged`만 반환한다. FE 타입·어댑터·store·초안 저장에서도 시각 필드를 제거했다.

이번 수정 파일: BE `Diary`, `DiaryEmotionService`, `DiaryView`, `DiaryResponse`, `DiaryApiIntegrationTest`, `OpenApiContractIntegrationTest`, 이 문서. 신규 파일: V53 migration, `diary/infrastructure/DiaryEmotionFlagMigrationIntegrationTest`. FE `diaryApi.ts`, `diary.ts`, `types.ts`, `retro/draft.tsx`, 감정 편집 통합 테스트, `docs/{DESIGN,TASKS,ANALYTICS}.md`, `docs/api/{README.md,openapi.json}`. 삭제 파일 없음.

집중 검증 `./gradlew test --tests '*DiaryApiIntegrationTest' --tests '*OpenApiContractIntegrationTest' --tests '*DiaryEmotionFlagMigrationIntegrationTest' --tests '*DiaryEmotionSelectionTest'` 통과. PostgreSQL V52→V53 실마이그레이션 테스트는 건너뛰지 않고 실행·성공했으며 기존 true/false 이력 보존, 기본값 false, 시각 컬럼 제거, NOT NULL·스냅샷 제약을 검증했다. API는 시각 필드가 응답/스키마에 없음을 검증했다. FE 타입 검사·변경 파일 린트·관련 4개 스위트 79개 테스트 통과.


변경 여부 전환 최종 판정: **PASS**. 최종 `./gradlew clean check` 성공: 126개 스위트·1,139개 테스트, 실패·오류 0, 건너뜀 32. 독립 리뷰 2회, 기존 문서의 시각 필드 설명 Minor를 수정·재검토했으며 미해결 Critical/Major/Minor와 기각 사항 없음. API/OpenAPI·PostgreSQL 마이그레이션/JPA·소유권·동시성·롤백 검증 완료. 로컬 서버 재시작으로 V53을 적용하고 실제 일기 GET 200에서 boolean 필드와 시각 필드 부재를 확인했다. 실제 DB 컬럼도 `emotion_classification_changed BOOLEAN NOT NULL`만 존재한다. FE 공식 `MOMENTORY_API=http://127.0.0.1:8080 npm run sync:api`로 최종 스냅샷 재반입 완료, 두 저장소 diff 검사 통과.

이번 변경은 커밋·PR 검토 가능한 상태다. 커밋·푸시·머지는 하지 않았다. 운영 배포는 수행하지 않았으며, V53에서 옛 시각 컬럼을 제거하므로 구버전 서버를 계속 실행하는 방식 대신 새 서버와 DB 변경을 함께 반영해야 한다. 이 변경에 유료 LLM 재실행은 필요하지 않아 실행하지 않았다.


## 2026-11-18 최종 기준: 현재 감정과 최초 감정 비교

사용자 후속 지시가 앞의 누적 이력 기준을 대체한다. 최초 분류 감정 중 하나라도 현재 일기 전체에 없으면 true, 모두 있으면 false다. 복원 시 false로 돌아가고 다시 제거하면 true가 된다. 사건 연결만 옮기거나 새 감정을 추가한 것은 제외한다. 원본 스냅샷은 계속 유지하며 현재 저장 상태와 같은 트랜잭션에서 flag를 매번 갱신한다. 동시 저장은 기존 일기 행 잠금으로 직렬화하며 마지막 저장 결과에 맞는 flag가 남는다.

V52·V53은 변경하지 않고 V54로 기존 flag를 원본·현재 JSON의 감정 집합 포함 여부로 다시 계산한다. 원본/현재 스냅샷이 없는 일기와 직접 쓴 일기는 false다. API 필드·경로·메서드·오류 계약은 바꾸지 않고 설명만 갱신한다. UI와 API의 boolean 이름은 동일하다.

수정: `Diary`, `DiaryEmotionSelection`, `DiaryResponse`, `DiaryEmotionSelectionTest`, `DiaryApiIntegrationTest`, V53 단계에 고정한 `DiaryEmotionFlagMigrationIntegrationTest`. 신규: V54, `DiaryEmotionCurrentFlagMigrationIntegrationTest`. FE 타입 주석·디자인·API·개인정보·계측·제품·작업 문서 갱신. 삭제 파일 없음.

집중 검증 `./gradlew test --tests '*DiaryApiIntegrationTest' --tests '*DiaryEmotionSelectionTest' --tests '*DiaryEmotion*MigrationIntegrationTest' --tests '*OpenApiContractIntegrationTest'` 성공. false→true→false, 감정 일부 누락, 사건/비연결 사이 이동, 추가만 함, 전체 해제, 동시 저장·롤백과 실제 PostgreSQL V53→V54의 복원/누락/이동/전체 해제/원본 감정 없음/스냅샷 없음을 검증했다.

프론트 `diaryEmotionStore.test.ts`에 true 상태에서 최초 감정을 복원한 저장 응답 false가 그대로 반영되는 회귀 검증을 추가했다. 관련 3개 스위트·44개 테스트, `npm run typecheck`, 변경 파일 ESLint 통과. 서버 로직은 클라이언트가 계산하지 않는다.


현재 감정 기준 최종 판정: **PASS**. 최종 `./gradlew clean check` 성공: 127개 스위트·1,142개 테스트, 실패·오류 0, 건너뜀 32. V53→V54 마이그레이션 테스트 실제 실행·성공(건너뜀 없음). 독립 리뷰 2회, 미해결 Critical/Major와 기각 사항 없음. API/OpenAPI·PostgreSQL/JPA·소유권·동시성·롤백 게이트 통과. 공식 `MOMENTORY_API=http://127.0.0.1:8080 npm run sync:api`로 최종 스냅샷 반입, 두 저장소 `git diff --check` 통과. 로컬 서버를 재시작해 V54 적용을 확인했고 실제 일기 GET 200과 boolean 필드·시각 필드 부재를 확인했다.

기존 일기의 원본·현재 감정과 본문은 바꾸지 않고 변경 여부만 현재 비교 기준으로 재계산했다. 커밋·PR 검토 가능한 상태이며 커밋·푸시·머지는 수행하지 않았다. 운영 배포와 유료 모델 재실행은 수행하지 않았다. 운영 반영 시 V54과 새 서버 로직을 함께 반영해야 한다.


## 기능별 커밋 정리 (2026-11-18)

사용자의 커밋 요청에 따라 최종 검증된 변경을 기능별로 나눴다.

- BE `27ceacc`: 사건별 감정 수정 API·원본/현재 선택·현재 분류 변경 여부와 V52~V54.
- BE `bb92ebd`: 주요 사건 최대 두 개 선정·일기 생성 범위·생성 본문 보존 및 복구.
- FE `c0f106e`: 두 상세 화면의 감정 편집·사건 없는 감정·사진 스티커 배치·API 연동.
- FE `8ca3b55`: 주요 사건 선정 기준의 디자인·제품·작업 문서.
- 이 검증 기록은 위 기능을 함께 설명하는 별도 문서 커밋으로 남긴다.

커밋 준비 과정에서 제품 코드는 바꾸지 않았다. FE의 사진 스티커 문단만 주요 사건 절에서 감정 편집 절로 옮겼다. 서버 최종 전체 검증 127개 스위트·1,142개 테스트(실패·오류 0, 건너뜀 32), 프론트 최종 관련 3개 스위트·44개 테스트·타입·변경 파일 린트 통과 결과가 그대로 적용된다. 커밋 대상 diff 검사 통과.

기존 Firebase·개발 로그인·빌드 버전 관련 프론트 미커밋 변경은 공유 문서에서도 분리해 보존했다. 푸시·PR 생성·머지는 수행하지 않았다.
