# 직접 일기 쓰기 구현·검증 기록

2026-11-01 · FE/BE 브랜치: `feat/retro-writing-method`

결과: **PASS WITH WARNINGS**. 구현·API/OpenAPI·PostgreSQL 영속성 검증과 독립 검토를 완료했다.
네이티브 실기기에서 키보드와 입력 길이별 최종 확인, 배포 서버의 OpenAPI 스냅샷 반입은 남아 있다.
검증 완료 후 사용자 요청으로 기능별 로컬 커밋을 진행한다. 푸시·PR·머지·운영 배포는 이 기록의 범위에 포함하지 않는다. 소스는 PR 검토 가능한 상태다.

## 구현

- `하루 돌아보기`는 기존 일정 확인 화면에서 같은 크기의 `AI와 대화하기` / `직접 일기 쓰기`를 제공한다.
  일정이 없어도 둘 다 선택할 수 있다. 오늘 일기를 이미 쓴 경우의 기존 안내는 유지한다.
- 직접 쓰기는 빈 종이 일기장, 오늘 일정, 500자 상한, 공백만 있는 본문 저장 방지를 제공한다.
  서버 저장 성공 후 일기 상세를 연다. 저장 실패 시 본문 유지, 연속 누름 차단,
  화면이 닫힌 뒤 응답이 도착할 때 뒤늦은 화면 이동 방지를 검증했다.
- `POST /api/v1/diaries`는 인증된 사용자로 직접 일기를 저장한다. AI 세션·감정 추론·카드·자동 편지는 생성하지 않는다.
  `PUT /api/v1/diaries/{id}`로 소유한 일기 본문만 수정한다. 기존 수정 API는 유지한다.
- 직접 쓴 일기도 조회·수정·삭제할 수 있다. 응답의 회고 id가 없으면 사용자 작성으로 읽는다.
  저장소 로딩을 기다리는 사이 저장한 일기가 이전 조회 응답으로 사라지지 않게 한다.
- V33은 `retrospect_id`의 NOT NULL만 해제한다. PK·user FK·retrospect FK·기존 회고당 UNIQUE는 유지한다.
  사용자 행 잠금으로 직접 저장과 AI 완료의 하루 한 장 규칙을 직렬화한다. 하루 경계는 기존 04:00 KST다.
  이미 직접 저장한 날 AI 세션을 완료하면 409를 반환하고 세션 진행 변경도 롤백한다.

## 실행한 검증

| 명령 | 결과 |
| --- | --- |
| FE `npm run typecheck` | 통과 |
| FE `npm run typecheck:lab` | 통과 |
| FE `npm run typecheck:expo-lab` | 통과 |
| FE `npm run lint` | 통과 |
| FE `npm run format:check` | 통과 |
| FE `npm test -- --runInBand --ci` | 132 스위트 · 1,683 테스트 통과 |
| BE `./gradlew compileJava compileTestJava --offline` (Java 21) | 통과 |
| BE `./gradlew clean test --tests '*DiaryApiIntegrationTest' --tests '*OpenApiContractIntegrationTest' --offline` | PostgreSQL 통합 테스트 통과 |
| BE `./gradlew --init-script /tmp/momentory-direct-diary-verification/export.gradle clean check --offline` (Java 21) | 통과 · XML 합계 89 스위트, 750 항목, 실패/오류 0, 건너뜀 14 |
| 두 저장소 `git diff --check` | 통과 |

최종 BE 실행은 저장소 전체 검증과 함께 임시 `/tmp` 소스의 OpenAPI 응답 추출 테스트 1개를 실행했다.
임시 소스·설정·응답은 저장소 변경에 포함하지 않는다. 로그는 `/tmp/momentory-direct-diary-verification/check.log`에 있다.
건너뛴 항목은 기존 외부 AI 실연동 테스트 등이며 실제로 실행한 PostgreSQL/API 계약 테스트는 통과했다.

API/OpenAPI 게이트: 직접 작성·수정 DTO의 실제 필드, 성공 스키마, 400/401/404/409 오류 스키마·코드·문구·예제를
`/v3/api-docs` 계약 테스트로 확인했다. 인증 없는 생성, 본문 누락/공백/500자 초과, 다른 사용자 수정,
직접 쓰기 동시 요청, AI 완료와의 충돌·롤백, 저장 후 재조회·수정·삭제를 검증했다.

초기 실패 두 종류는 수정 후 전체 검증으로 해소했다. 새 마이그레이션 번호가 기존 V32와 충돌해 V33으로 바꿨고,
회고 메시지의 새 409 응답을 계약 테스트의 허용 상태 목록에도 반영했다.

Lab에서 실제 공유 선택 컴포넌트의 일정·두 카드 배치를 브라우저로 확인했다.
직접 쓰기 화면은 렌더링·라우팅·저장 동작 테스트로 검증했으며 네이티브 실기기 검증은 수행하지 않았다.

## 독립 검토

별도 읽기 전용 리뷰어가 **2회** 전체 diff와 신규 파일을 검토했다.
1차의 Major 2개(직접 저장 후 AI 완료의 409 문서/롤백 검증 누락, 초기 조회가 새 일기를 지우는 경쟁)를 모두 수정하고
회귀 테스트를 추가했다. 2차는 두 수정의 반영을 확인했으며 남은 substantiated finding은 없었다.
기각한 지적은 없다.

## 배포·확인 사항

- 서버 V33 및 새 저장/수정 API를 먼저 배포한 뒤 앱을 배포해야 한다.
- 기존 `docs/api/openapi.json`은 배포 서버 반입물이라 손으로 고치지 않았다.
  테스트 컨텍스트에서 추출한 응답에는 테스트 전용 컨트롤러가 포함되어 반입하지 않았다.
  기능을 배포한 서버에서 FE `npm run sync:api`를 실행해 정식 스냅샷을 갱신한다.
- 실제 기기의 키보드 표시·긴 본문 입력·뒤로가기 배치는 최종 확인이 필요하다.

## 변경 파일

`M`은 기존 파일 수정, `??`는 새 파일이다. 삭제 파일은 없다.

### momentory-fe

- `M` `app/retro/__tests__/retro.test.tsx`
- `M` `app/retro/confirm.tsx`
- `M` `docs/DESIGN.md`
- `M` `docs/PRODUCT.md`
- `M` `docs/api/README.md`
- `M` `expo-lab/src/retro/RetroDiaryPreview.tsx`
- `M` `src/features/analytics/events.ts`
- `M` `src/features/analytics/observation.ts`
- `M` `src/features/diary/data/__tests__/diaryApi.test.ts`
- `M` `src/features/diary/data/diaryApi.ts`
- `M` `src/features/diary/model/__tests__/diaryWrite.test.ts`
- `M` `src/features/diary/model/__tests__/sessionGuard.test.ts`
- `M` `src/features/diary/model/diary.ts`
- `M` `src/features/diary/model/types.ts`
- `??` `app/retro/__tests__/write.test.tsx`
- `??` `app/retro/write.tsx`
- `??` `src/features/retro/ui/RetroMethodChoices.tsx`

### momentory-be

- `M` `src/main/java/com/momentory/diary/application/DiaryFromRetrospectListener.java`
- `M` `src/main/java/com/momentory/diary/application/DiaryService.java`
- `M` `src/main/java/com/momentory/diary/domain/Diary.java`
- `M` `src/main/java/com/momentory/diary/presentation/DiaryController.java`
- `M` `src/main/java/com/momentory/diary/presentation/DiaryExceptionHandler.java`
- `M` `src/main/java/com/momentory/diary/presentation/DiaryResponse.java`
- `M` `src/main/java/com/momentory/letter/application/LetterMaterialsReader.java`
- `M` `src/main/java/com/momentory/letter/application/LetterWishCards.java`
- `M` `src/main/java/com/momentory/retrospect/presentation/RetrospectController.java`
- `M` `src/main/java/com/momentory/user/infrastructure/UserRepository.java`
- `M` `src/test/java/com/momentory/config/OpenApiContractIntegrationTest.java`
- `M` `src/test/java/com/momentory/diary/presentation/DiaryApiIntegrationTest.java`
- `M` `src/test/java/com/momentory/retrospect/presentation/RetrospectApiIntegrationTest.java`
- `??` `src/main/java/com/momentory/diary/application/DiaryCreationGuard.java`
- `??` `src/main/java/com/momentory/diary/presentation/DiaryWriteRequest.java`
- `??` `src/main/resources/db/migration/V33__allow_diaries_without_retrospect.sql`

이 검증 기록 파일도 새로 추가했다.


## 후속 요청 — 사진 감정 선택·무감정·사건 저장·선택지 아이콘

- 직접 쓰기에서는 처음에 점선 사진 자리를 표시하고 누르면 감정 선택 시트를 연다.
  감정 10종 중 하나를 고르면 해당 사진, `선택 안 함`을 고르면 무감정 사진을 표시한다.
  이 선택은 본문과 함께 저장하고 모델 결과로 바꾸지 않는다. 시트를 닫거나 감정을 바꿔도 본문은 유지한다.
- AI 대화 선택지의 오른쪽은 기존 투명 PNG의 바바 얼굴을 원형으로 표시한다.
  흰 배경을 제거했고 귀를 포함하도록 크롭을 넓혔다. 직접 쓰기에는 기존 연필 아이콘을 사용한다.
- `DiaryCreateRequest`는 `original`과 선택 `emotion`만 받는다. 수정 요청은 기존 `DiaryWriteRequest`의 본문만 받는다.
  잘못된 감정 키는 400 `INVALID_REQUEST` / `감정을 확인해주세요.`다. DTO·성공/오류 스키마와 예제를 계약 테스트로 검증했다.
- `WrittenDiaryService`는 직접 쓴 본문을 기존 G1 추출기에 한 번 보내 최대 두 사건을 뽑는다.
  호출은 DB 트랜잭션·사용자 행 잠금 밖에서 수행하고, 사건은 V34 `diaries.events_json`으로 본문과 함께 저장한다.
  사건 이름·요약·본문에 실제 근거가 있는 감정만 저장하며 추론 감정을 사용자 기록으로 취급하지 않는다.
  G1 실패나 사건 없는 글은 빈 사건 목록과 본문을 저장한다. 별도 회고 세션은 만들지 않는다.
- 직접 쓴 본문을 수정하면 사건도 교체한다. 본문과 사건은 같은 트랜잭션에서 바뀌어 서로 다른 버전이 섞이지 않는다.
  `LetterMaterialsReader`가 직접 작성 사건을 읽고 편지의 욕구·행동 추천 프롬프트로 넘긴다.
  채팅 일기는 기존 회고 상태에서 사건을 읽는다. 직접 작성·수정의 앱 대기 상한은 서버 G1 제한(30초)을 고려한 40초다.
- 새로운 검토 1차에서 발견한 사용자 번호 목록의 프롬프트 누락을 수정했다.
  `PromptFactory.numberedHistory`는 AI 선택지 줄만 제거하고 사용자 본문은 보존한다.
  실제 G1 프롬프트를 검증하는 회귀 테스트를 추가했다. 본문 밖 감정 근거를 거절하는 DB 테스트도 추가했다.
  읽기 전용 리뷰어의 후속 2차 검토에서 남은 substantiated finding은 없었다.

후속 FE 검증: 앱·두 Lab 타입 검사, lint, format 검사 통과. 전체 132 스위트 / 1,685 테스트 통과.
후속 BE 표적 검증: Diary API·OpenAPI·LetterMaterialsReader·GeminiLetterWriter·PromptFactory 통과.
최종 BE `JAVA_HOME=.../temurin-21.jdk/Contents/Home ./gradlew clean check --offline` 통과.
XML 기준 89 스위트 / 757 항목, 실패·오류 0, 기존 실모델 테스트 등 14 항목 건너뜀.
로그: `/tmp/momentory-written-events-check.log`. 최종 결과는 PASS WITH WARNINGS이며,
실기기 화면 확인·개발 서버 배포·정식 OpenAPI 스냅샷 반입은 남아 있다.

추가 소스: `WrittenDiaryService`, `DiaryEvent`, `DiaryEventCodec`, `DiaryCreateRequest`, V34,
`LetterMaterialsReaderTest`. 기존 사진 컴포넌트·직접 작성 화면·편지 재료/추천 프롬프트와 관련 테스트를 함께 수정했다.
기존 서버에 적용하려면 V33·V34와 새 API를 배포해야 한다. 실모델 추출 정확도는 이 검증에서 보장하지 않는다.
