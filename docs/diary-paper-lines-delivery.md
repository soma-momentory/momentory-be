# 일기 종이 디자인과 문장별 줄바꿈 — 2026-10-31

> 아래 본문은 10월 31일 최초 검증 기록이다. 후속 구현과 최종 검증은 마지막의 11월 1일 절에 기록한다.

결과: **PASS WITH WARNINGS**. 커밋·PR 검토 가능. 커밋, 푸시, 병합, 배포는 하지 않았다.

## 동작과 범위

- 채팅 완료 후 일기와 보관함 일기 상세에 승인된 종이 디자인을 공유한다. 조약돌 감정 스티커를 누르면 사진 위에 감정 이름을 표시하고, 제공받은 여섯 감정 사진을 연결한다. 일정은 매끈한 연녹색 메모지로 표시한다.
- BE의 일기 생성 및 분량 수정 지침에 문장마다 줄바꿈, 같은 맥락에서는 빈 줄 없이 이어 쓰기, 맥락이 바뀌면 빈 줄 하나 넣기를 적용했다. 기존 5~6문장 분량과 사실 기반 작성 규칙은 유지한다. 채팅 응답 지침은 바꾸지 않았다.
- FE는 본문의 문장 줄바꿈과 문단 사이 빈 줄을 읽기·편집·저장·API 매핑에서 그대로 보존한다. 사용자 문장과 기존 일기를 구두점 기준으로 다시 나누지 않는다.
- API 요청/응답, 인증, DB 스키마, 마이그레이션 변경 없음. 해당 계약 변경 게이트는 적용 대상이 아니다.

## 브랜치

초기 UI 작업은 기존 FE `feat/retro-diary-stickers`에서 진행했다. 이번 요청을 받고 FE와 BE 모두 새 `feat/diary-paper-lines`를 만들었다. BE 시작점은 기존 `feat/retro-diary-five-turns`이며 FE의 미커밋 UI 변경은 새 브랜치에 그대로 보존했다. 처음부터 양쪽 새 브랜치에서 시작한 것은 아니다.

## 변경 파일

BE 수정:

- `src/main/java/com/momentory/retrospect/infrastructure/ai/PromptFactory.java`
- `src/main/java/com/momentory/retrospect/infrastructure/ai/GeminiDiaryWriter.java`
- `src/test/java/com/momentory/retrospect/infrastructure/ai/PromptFactoryTest.java`
- `src/test/java/com/momentory/retrospect/infrastructure/ai/GeminiDiaryWriterTest.java`
- `src/test/java/com/momentory/retrospect/infrastructure/ai/GeminiDiaryWriterRealIntegrationTest.java`

BE 생성: 이 검증 보고서. 삭제 없음.

FE 생성:

- `src/components/diary/DiaryPaper.tsx`, `diaryPhotos.ts` 및 해당 컴포넌트 테스트
- `src/design/ds/diaryPaper.ts`
- `assets/diary/photos/`의 감정 사진 여섯 장과 추가 방법 README
- `expo-lab/src/diary/DiaryPaperPreview.tsx`

FE 수정:

- `app/retro/draft.tsx`, `app/diary/[id]/index.tsx`
- `app/retro/__tests__/diary.test.tsx`, `app/diary/__tests__/detail.test.tsx`
- `src/features/diary/data/__tests__/diaryApi.test.ts`, `src/lib/day.ts`
- `expo-lab/src/App.tsx`, `expo-lab/src/catalog/PhonePreview.tsx`, `expo-lab/src/catalog/variants.tsx`
- `docs/DESIGN.md`, `docs/PRODUCT.md`

FE 삭제 없음. 사용자 제공 원본 사진은 수정하지 않았다.

## 실행한 검증

BE:

```sh
./gradlew test --tests '*PromptFactoryTest' --tests '*GeminiDiaryWriterTest' --tests '*GeminiDiaryWriterRealIntegrationTest' --no-daemon
./gradlew clean check --no-daemon
```

둘 다 성공. 전체 검사 결과 743개 중 729개 통과, 14개 스킵, 실패·오류 0개. Docker 기반 통합 검사도 실행됐다. 스킵에는 실제 Gemini 호출 테스트와 기존 프롬프트 예시 테스트가 포함된다.

FE:

```sh
npm test -- --ci --runInBand --silent app/retro/__tests__/diary.test.tsx app/diary/__tests__/detail.test.tsx src/features/diary/data/__tests__/diaryApi.test.ts
npm run typecheck
npm run typecheck:expo-lab
npm run typecheck:lab
npm run lint
npm run format:check
npm test -- --ci --runInBand --silent
```

전부 성공. 집중 검사 3개 스위트 83개 통과. 전체 검사 131개 스위트 1,650개 통과.

양쪽 `git status --short`, `git branch --show-current`, `git diff --check` 확인. 공백 오류 없음. 최종 코드 변경 뒤 전체 검사를 수행했다.

## 리뷰와 제한

별도 읽기 전용 리뷰어가 양쪽 AGENTS 지침, 전체 변경 및 미추적 파일, 생성·저장·표시 경로를 검토했다. 코드 리뷰 1회에서 근거 있는 Critical/Major/Minor 지적 없음. 수정하거나 반려한 리뷰 지적도 없다. 보고서 추가 후 문서와 최종 상태를 재확인한다.

- `GEMINI_API_KEY`가 없어 실제 모델 출력은 검증하지 못했다. 생성·재작성 프롬프트와 줄바꿈 보존 테스트는 검증했다.
- 실제 네이티브 기기의 최종 UI는 검증하지 못했다. 앞선 디자인 구현은 Expo 웹 미리보기에서 확인했다.
- 아직 받지 않은 감정 사진은 기존 부분 매핑 구조로 나중에 추가할 수 있다.

## 2026-11-01 최종 정리와 기능별 커밋

- 사용자 승인에 따라 FE는 사진·조약돌 일기 디자인, 보관함 본문 편집, 저장 완료 화면 정리의 세 커밋으로 분리했다. 분리할 때 작업 파일은 유지하고 Git 인덱스에 기능별 스냅샷을 구성했다.
- 디자인에는 감정 10종과 무감정 사진 총 11장, 조약돌 이름 표시, 매끈한 일정 메모, 문장 줄바꿈 보존이 포함된다. 무감정은 새 감정 id를 만들지 않고 빈 감정 배열의 사진으로만 표시한다.
- 보관함 편집은 기존 회고 ID 기반 PUT API를 사용한다. 서버 저장 후 반영, 입력 보존과 재시도, 취소, 중복 저장 방지 및 세션 변경 응답 무시를 포함한다. 삭제 왼쪽의 연필 아이콘으로 연다. BE API 변경은 없다.
- 저장 완료 화면은 그림을 제거하고 안내·카드를 남은 영역 가운데에 배치한다. 버튼은 화면 하단에 고정한다.
- FE 전체 검증 재실행: 앱·Vite Lab·Expo Lab 타입 검사, lint, format 검사 및 131개 스위트 **1,668개 테스트 전부 통과**.
- 분리한 디자인 스냅샷을 임시 폴더로 내보내 앱·Expo Lab 타입 검사와 집중 테스트 **97개 통과**. 편집 스냅샷도 같은 타입 검사와 편집·쓰기 테스트 **46개 통과**. 마지막 완료 화면 테스트 **36개 통과**.
- Android `npm run android -- --device SM_S931N --port 8081` 빌드 성공, USB 실기기에 설치 및 실행을 확인했다. 최종 UI의 자동 시각 회귀 검사는 수행하지 않았다.
- BE 전체 재검사에서 기존 `WeeklyReportApiIntegrationTest.omittedDateFallsBackToThisWeek`가 KST 일요일 00~04시에 실패하는 문제를 발견했다. 서비스는 `DayBoundary.today()`를 사용하지만 테스트 기대값은 자정 기준 `LocalDate.now(...)`를 사용했다. 테스트 기대 날짜만 기존 04:00 정책에 맞춰 수정했다. 프로덕션 코드·API·DB 변경 없음. 이 정정은 별도 테스트 커밋으로 분리한다.
- 정정 후 `./gradlew test --tests '*WeeklyReportApiIntegrationTest' --tests '*PromptFactoryTest' --tests '*GeminiDiaryWriterTest' --no-daemon` 성공. 이어 `./gradlew clean check --no-daemon` 최종 성공: **743개 중 729개 통과, 14개 스킵, 실패·오류 0개**.
- BE는 날짜 경계 테스트 정정과 일기 문장별 줄바꿈 생성 규칙을 각각 커밋한다. API·인증·DB 스키마 변경은 없으며 관련 계약 변경 게이트는 적용 대상이 아니다.
- 검증 제한: 실제 Gemini 호출은 API 키가 없어 미실행. 최종 결과 **PASS WITH WARNINGS**. 로컬 커밋과 PR 검토 가능, 푸시·배포·병합은 수행하지 않는다.
- 별도 읽기 전용 리뷰 총 3회 완료(최초 코드, 문서·상태, 최종 테스트 정정·커밋 경계 검토). 최종 리뷰에서도 근거 있는 Critical/Major/Minor 지적 없음. 최종 BE XML 집계와 리포트 통합 테스트 12개 통과, FE 작업 트리 및 커밋별 범위를 독립 확인했다.
