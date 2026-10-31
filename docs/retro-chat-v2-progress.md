# 회고 채팅 v2 — 진행상황 & 이어서 할 일 (핸드오프)

## 2026-10-30 감정·계기 3분기 및 다른 이야기 거절 후 즉시 감정 탐색

- 최신 사용자 결정: 감정이 없으면 기분 질문, 감정만 있으면 계기 질문 한 번, 감정과 이유가 함께면
  질문 없이 공감만 반환한다. 앞선 ‘감정 확인 뒤 바로 다른 소재’ 정책은 이 결정으로 대체한다.
- G2 내부 출력에 `emotionCausePresent` boolean을 추가하고, 감정·계기 분류를 텍스트 생성보다
  먼저 출력하도록 propertyOrdering을 조정했다. 감정이 행동의 원인인 것과 감정이 든 원인의 설명을 구분하며,
  이전 사건만으로 사용자의 감정 계기를 추정하지 않는다. 특정 사람의 표현 예시는 넣지 않았다.
- `topicCauseDiscussed`를 새 소재에서 초기화하고 snapshot에 nullable Boolean으로 저장한다.
  구버전은 false로 복원한다. 계기를 물었거나 이미 제공받았으면 반복 질문하지 않는다.
  감정 없는 유효한 후속 응답도 생각·원인이 아닌 기분 질문으로 안내한다.
- 다른 기억 질문과 두 사건 이후 다른 이야기 제안에 명확한 없음·거절로 답하면 G2를 재호출하지 않고
  초안을 생성해 감정 탐색으로 직행한다. 사건이 둘이어도 선택을 다시 권하지 않고 기존 mainEvent 정책
  (근거 수 최다, 동점 첫 사건)으로 대상을 정하며 감정 질문에 사건 이름을 표시한다.
- 거절 문맥 밖의 일반 종료·질문·새 소재·이중 부정과 안전 규칙은 유지한다. 거절에는 없는 감정을
  추정하지 않는 중립적 마무리를 쓴다. 사건이 추출되지 않거나 위기 안내 후인 기존 일기 완료 정책은 유지한다.
- 공개 API 경로·필드·상태/오류 계약·인증·JPA/DDL/Flyway 변경 없음. snapshot 내부 필드만 호환 확장했다.
  선행 미커밋 변경을 보존했으며 원격 배포·커밋·푸시는 하지 않는다.
- 이번 수정 BE: `RetrospectEngine.java`, `RetrospectState.java`, `RetrospectStateSnapshot.java`,
  `DiaryTurn.java`, `GeminiResponseSchemas.java`, `PromptFactory.java`, `FakeAssistant.java`, 엔진·도메인·
  codec·G2 스키마·prompt·REST·실모델 검사와 계획·진행 문서. FE: `docs/DESIGN.md`, 계획 문서.
  이번 묶음의 신규/삭제 파일 없음. 직전 FE의 말풍선 경계 수정은 보존했다.
- 독립 리뷰 1차 Major: 3턴 이후 짧은 유효 계기 답변이 단답 체크인에 걸렸다. `causeProvided`를
  체크인에서 제외하고 ‘비’처럼 짧은 답도 공감만 하는 회귀를 추가해 집중 검사로 확인했다.
- 집중 6종 검사(engine/state/codec/prompt/API/G2 adapter) 통과(12초). 이전 정책을 기대한 검사들은
  새 요구에 맞춰 기대값과 원인 질문 단계를 갱신했고, 기존 안전·최대 턴·일반 종료 검사는 유지했다.
  구버전 복원·새 소재 초기화·감정+계기 공감 전용·짧은 계기·두 사건 거절 즉시 탐색·DB snapshot을 확인했다.
- 실모델 검사에서 짧은 기분 답변에 이전 사건을 원인으로 추정하거나 두 boolean과 질문 목적이 어긋난
  사례를 발견해 분류 근거와 출력 순서를 보강했다. 초기 실패 실행은 성공으로 기록하지 않는다.
- 최종 실제 G2+prompt `./gradlew test --tests '*GeminiDiaryChatAssistantRealIntegrationTest'
  --tests '*PromptFactoryTest' --no-daemon` 통과(22초). 공개 가상 실제 모델 12사례 모두 통과,
  prompt 24건 중 기존 환경 의존 1건 스킵. 아직 전 일정·실제 경험 혼합·감정 없음·감정만·감정+이유·말투 변형 확인.
  ‘어떤 순간에 편안했는가’처럼 계기·상황을 묻는 정상 표현도 인정하도록 계기 질문 검사를 일반화했다.
- 전체 프롬프트 열람 파일 `/private/tmp/momentory-current-prompts-2026-10-30.txt`를 최종 지시와 일치하게 갱신했다.
- 최종 집중 `./gradlew test --tests '*RetrospectEngineTest' --tests '*RetrospectStateTest'
  --tests '*RetrospectStateCodecTest' --tests '*PromptFactoryTest' --tests '*RetrospectApiIntegrationTest'
  --tests '*GeminiDiaryChatAssistantTest' --no-daemon` 통과(12초). 6개 스위트 총 187건 중
  **186개 통과 / 실패·오류 0 / 기존 환경 의존 1건 스킵**.
- 2차 독립 리뷰에서 1차 Major 수정 확인, 남은 Critical/Major/Minor 없음. ‘더 말할 건 없어’ 등도
  다른 이야기 제안 문맥에서는 일반 전체 종료로 빠지지 않고 감정 탐색으로 가도록 거절 대상 명사와
  끝맺음의 경계를 보완하고 3사례 회귀를 추가했다. 최종 전체 검사를 이 마지막 코드 변경 후 재실행한다.
  보완 전 시작한 전체 검사 1회는 중단했으며 성공으로 기록하지 않는다.
- 마지막 코드 변경 후 전체 `./gradlew clean check --no-daemon` 통과(1분 27초).
  88개 스위트 총 743건: **729개 통과 / 실패 0 / 오류 0 / 스킵 14**.
  기본 실행의 실제 Gemini·외부 challenge 옵트인 노드는 스킵하며 실제 G2 12사례는 위 별도 실행에서 통과했다.
- 독립 읽기 전용 리뷰 총 3회. 1차 Major(짧은 계기 체크인) 1건을 수정·회귀 검증했고,
  2·3차 남은 Critical/Major/Minor 없음. 반려한 지적 없음. 최종 전체 검사 통과도 이후 확인했다.
- 최종 변경·untracked 확인 및 양쪽 `git diff --check`, 변경 FE 문서 Prettier 통과.
  공개 API·인증 계약을 바꾸지 않았고 실제 PostgreSQL REST/snapshot·legacy codec 호환 검사를 포함했다.
- 최종 코드로 로컬 BE 서버 재시작, `/actuator/health` UP과 Expo `packager-status:running` 확인.
  다만 마지막 확인에서 ADB 기기 목록이 비어 있고 USB 기기도 감지되지 않아 실기기 재연결 확인은 못 했다.
  기기 데이터·개인 대화를 대신 변경하지 않았고 앱 강제 재시작도 하지 않았다. 원격 배포·커밋·푸시 없음.
- 결과: **PASS WITH WARNINGS**. 요청 구현·집중/전체 검사·독립 리뷰 완료, 커밋·PR 검토 준비 상태다.
  생성형 AI의 모든 표현에 대한 분류 정확성을 보장하는 검증은 아니다. 없는 사건·위기 안내 시의 기존 완료 경로,
  5턴 상한은 우선한다. 실기기 USB 재연결 및 사용자 본인의 최종 대화 확인이 남는다.

## 2026-10-30 다른 기억 없음의 과거형 답변 반복 수정

- 실기기 제보의 원인: `declinesAnotherMemory`가 현재형 ‘없어/없어요’만 받아서 과거형 ‘없었어’는
  종료 신호가 되지 않았다. 따라서 G2를 다시 호출한 뒤 같은 다른 기억 질문이 돌아왔다.
- 부정의 시제를 `없(?:었)?`로 일반화하고 응답 앞의 긍정/부정 맞장구, 추가 없음 부사,
  강조 부사, 대상 표현을 각각 선택적으로 조합한다. 특정 사용자의 문장 하나만 추가하지 않는다.
- 직전 바바 메시지가 고정 다른 기억 질문으로 끝나는지 확인하고 답변 전체를 매칭하는 조건은 유지한다.
  새 경험을 뒤에 붙임, 질문, 이중 부정, 모호한 답변, 다른 질문에 대한 없음은 조기 종료하지 않는다.
  일치 시 G2 재호출 없이 기존 일기 초안 생성·감정 탐색 전환 경로를 따른다.
- 이번 수정 파일: `RetrospectEngine.java`, `RetrospectEngineTest.java`, `RetrospectApiIntegrationTest.java`,
  이 진행 문서. 선행 미커밋 변경 모두 보존, 신규·삭제 파일 없음. 공개 API·인증·DB·Flyway 변경 없음.
- 엔진 회귀 검사는 과거형·수식어 조합 및 새 이야기·물음표·이중 부정 경계를 추가했다.
  REST 기존 현재형 검사도 남기고 제보 문장과 수식어 조합을 매개변수화했으며,
  조기 전환·3턴 snapshot·초안 보존·완료 전 일기 테이블 미저장 검사를 유지했다.
- 집중 `./gradlew test --tests '*RetrospectEngineTest' --tests '*RetrospectApiIntegrationTest' --no-daemon`
  **115건 통과 / 실패·오류·스킵 0**(9초). G2 미재호출·초안 1회 생성, 기존 API·DB 동작 검증 포함.
- 독립 읽기 전용 리뷰 1회 Critical/Major/Minor 없음. 당시 진행 중인 검사·실기기 반영은 Unable to verify였고,
  이후 집중 검사 통과를 확인했다. 반려한 지적 없음.
- 최종 전체 `./gradlew clean check --no-daemon` 통과(1분 27초). 88개 스위트 총 717건 중
  **705개 통과 / 실패 0 / 오류 0 / 스킵 12**. 실제 Gemini 옵트인·외부 challenge 검사는 기본 스킵이며,
  이번 수정은 모델 호출 이전의 결정적 종료 판정이므로 엔진·실제 REST/DB 회귀로 검증했다.
- 독립 읽기 전용 최종 리뷰까지 총 2회, 남은 Critical/Major/Minor 없음. 당시 실기기 최종 응답은
  Unable to verify였으며 사용자의 실제 대화로 대신 재현하지 않았다. 반려한 지적 없음.
- 최종 코드로 실기기용 로컬 BE 서버 재시작 후 `/actuator/health` UP과 기기 USB reverse 8080/8081 유지 확인.
  FE 변경 없이 다음 요청부터 서버 수정이 적용된다. 앱 데이터·기존 대화·기록은 수정하거나 삭제하지 않았다.
- 최종 Git status/전체 변경·untracked 확인 및 `git diff --check` 통과. 원격 배포·커밋·푸시 없음.
- 결과: **PASS WITH WARNINGS**. 원인 수정·집중/전체 검사·독립 리뷰 완료로 커밋·PR 검토 준비 상태다.
  실제 기기에서 사용자 본인이 다시 답변한 최종 화면 전환은 별도 확인이 필요하다.

## 2026-10-30 일기 5~6문장·맥락별 문단 및 자유 대화 규칙 일반화

- G4 일기는 기본 5~6문장으로 작성하고 같은 맥락의 문장은 이어 쓴다. 사건·시간·상황이
  달라지면 빈 줄로 문단을 구분한다. 재료가 충분하면 문장 수를 확인하도록 하되,
  없는 사실·감정을 만들거나 잡일로 분량을 채우지 않는 원칙은 유지한다. 모델 장애 시 기존 최소 일기 대체 경로도 유지한다.
- FE 초안에서 모든 줄바꿈을 공백으로 접던 `asOneParagraph`와 폐기된 동작 전용 검사를 제거했다.
  초안 읽기·편집·저장·기존 상세 표시에서 원문 문단을 유지한다. 대신 화면과 저장 경로에서 회귀 검증한다.
- G2의 특정 상황 예시를 제거하고 시간 정정 / 실제 새 소재 / 같은 소재의 후속 감정 / 다른 소재 전환의
  우선순위와 공감 기준·출력 필드로 일반화했다. 예시에 없는 사용자 표현에도 같은 기준을 적용한다.
  추가 사용자 요청에 따라 말투·문장 길이·맞춤법·특정 단어보다 이전 대화의 의미·발생 여부·소재 연속성·감정을
  판단하도록 명시했다. 사용자 말투·문장 구조를 흉내 내거나 고정 공감 문구를 반복하지 않고 감정의 방향·강도에 맞춰 반응한다.
  시간 상태→소재 관계→질문 목적의 판단 순서로 다시 정리하고 첫 설명/후속 답변의 의미를 정의했다.
  상태와 중복되던 서버 진행 문구도 제거해 충돌을 줄였다. 최종 질문·종료는 기존 서버 규칙이 보장한다.
  첫 소재의 공감+감정 질문, 후속 감정 후 다른 기억 질문, 미래 일정 제외, 최대 두 사건·다섯 턴 정책은 유지한다.
- 실제 모델 검사에서 후속 감정 뒤 같은 사건의 세부 장면을 다시 묻는 경우를 발견해 탐색 종료와
  재질문 금지를 명시했다. 선택 필드였던 primitive boolean이 null로 생성된 1건도 발견해
  G2 내부 응답 스키마의 모든 boolean을 필수로 지정했다. 공개 API 필드·인증·DB·Flyway 변경 없음.
- 일기 실모델 검사에서 4문장으로 과도 압축하는 경우를 발견해 재료 충분 시 문장 수 확인과
  행동·감정의 과도한 압축 금지를 추가했다. 이후에도 4문장인 경우가 있어 어댑터에서 문장 수를 확인하고
  범위를 벗어나면 원래 대화·최초 초안만으로 한 번 검토하도록 보완했다. 검토는 읽기 제한 5초,
  실패 시 최초 초안을 유지한다. 재료 부족 시 짧아도 정확성을 우선한다. 정상 분량은 추가 호출하지 않는다.
  실패 실행을 성공으로 처리하지 않았다. `GeminiDiaryWriterTest`에 정상 무추가호출·분량검토·
  검토실패 보존·재료부족 무한재시도 방지·최초실패 대체 경로 검사를 추가했다.
- 이번 수정 BE: `PromptFactory.java`, `GeminiDiaryWriter.java`, `GeminiResponseSchemas.java`,
  `PromptFactoryTest.java`, `GeminiDiaryChatAssistantTest.java`, `GeminiDiaryChatAssistantRealIntegrationTest.java`,
  신규 `GeminiDiaryWriterRealIntegrationTest.java`, `GeminiDiaryWriterTest.java`, 계획·진행 문서. FE: `app/retro/draft.tsx`,
  `app/retro/__tests__/diary.test.tsx`, `app/diary/__tests__/detail.test.tsx`, `docs/DESIGN.md`, 계획 문서;
  삭제 `src/features/diary/model/body.ts`, `src/features/diary/model/__tests__/body.test.ts`.
  직전 요청의 미커밋 변경은 모두 보존했다. 전체 프롬프트 열람용 `/private/tmp/momentory-current-prompts-2026-10-30.txt`도 갱신했다.
- 집중 FE `npm test -- --runInBand app/retro/__tests__/diary.test.tsx app/diary/__tests__/detail.test.tsx`
  2개 스위트·53건 통과. 전체 `npm test -- --runInBand` 130개 스위트·1,638건 통과(18.3초).
  `npm run typecheck` 및 변경 FE 파일 Prettier 검사 통과.
- 초기 BE 집중 `./gradlew test --tests '*PromptFactoryTest' --tests '*RetrospectEngineTest' --no-daemon` 통과(4초).
- 말투 일반화 전 실모델+집중 검사 `./gradlew test --tests '*GeminiDiaryChatAssistantRealIntegrationTest'
  --tests '*GeminiDiaryWriterRealIntegrationTest' --tests '*GeminiDiaryChatAssistantTest'
  --tests '*PromptFactoryTest' --no-daemon` 통과(17초). 실행 프로세스에만 기존 키를 전달했고 개인 기록·키는 출력하지 않았다.
  자유 대화 공개 가상 7사례 및 2사건 일기 5~6문장·2문단·문단당 복수 문장을 확인했다.
- 독립 읽기 전용 리뷰 2회에서 Critical/Major/Minor 없음. 2차 당시 최신 검사 결과 미확인과
  생성형 AI의 분량 준수 변동성이 검증 한계로 남았다. 해당 실제 실행은 통과했으며 기계적 문장 자르기는 하지 않는다.
  이후 말투 일반화 검사에서 생각 질문이 생성된 1건을 발견해 첫 설명에서도 감정과 생각을 구분하도록 보강했다. 전체 검사·로컬 서버 반영 결과는 아래에 이어 기록한다.
- 추가 말투 일반화 전 전체 BE `./gradlew clean check --no-daemon`는 1분 28초에 통과했다.
  이후 사용자 요청으로 공통 지시와 의미 판단 규칙·말투 변형 검사를 추가했으므로 최종 전체 검사를 다시 실행한다.
- 최종 일반화 후 실모델+집중 검사 `./gradlew test --tests '*GeminiDiaryChatAssistantRealIntegrationTest'
  --tests '*GeminiDiaryWriterRealIntegrationTest' --tests '*GeminiDiaryWriterTest'
  --tests '*GeminiDiaryChatAssistantTest' --tests '*PromptFactoryTest' --no-daemon` 통과(22초).
  실제 모델 자유 대화 10사례(간단 구어체·정중체·오탈자 포함)와 문단 일기 1사례가 통과했다.
  특정 문장을 학습 예시로 프롬프트에 넣지 않고 의미·단계 판단 규칙만으로 검사했다.
- 최종 코드 전체 BE `./gradlew clean check --no-daemon` 통과(1분 27초).
  88개 스위트 총 703건: **691개 통과 / 실패 0 / 오류 0 / 스킵 12**.
  기본 실행에서 키가 없는 실제 Gemini 테스트 노드 및 외부 challenge 검사는 스킵한다.
  Gemini 자유 대화 10사례·일기 1사례는 위 별도 실제 호출 실행에서 통과했다.
- 독립 읽기 전용 리뷰 총 3회, 최종 Critical/Major/Minor 없음. 반려한 지적 없음.
  마지막 리뷰 당시 전체 검사와 실기기 최종 응답은 Unable to verify였고, 이후 위 전체 검사 통과를 확인했다.
  연결된 실기기의 개인 대화를 대신 생성하거나 기존 기록을 수정하지 않았다.
- 최종 코드로 로컬 BE 서버를 재시작하고 `/actuator/health`의 `UP` 확인. Expo `packager-status:running`,
  USB 기기 `R3CY106R8JL` 연결, 8080/8081 reverse 연결 유지 확인. 원격 배포·커밋·푸시하지 않았다.
- 최종 변경 파일·untracked 파일을 재확인하고 양쪽 `git diff --check`, 변경 FE 파일 Prettier 통과.
- 결과: **PASS WITH WARNINGS**. 요청 구현·필수 전체 검사·독립 리뷰 완료로 커밋·PR 검토 준비 상태다.
  생성형 AI의 모든 표현·분량 준수를 보장하는 검증은 아니다. 분량 재검토 후에도 재료가 부족하면
  정확성을 우선해 짧을 수 있고, 검토 호출이 필요한 경우 읽기 대기가 최대 5초 추가될 수 있다.
  실기기 최종 문단 배치와 새로운 개인 대화 응답은 사용자의 다음 테스트에서 확인할 수 있다.


## 2026-10-30 자유 대화 마무리와 공감·감정 질문을 한 응답으로 연결

- 작업 중 사용자가 추가한 요구를 함께 반영했다. 실제 경험 첫 답변은 구체적인 공감과 그때 마음을
  묻는 질문 하나를 같은 응답·말풍선으로 보낸다. ‘맞아’라는 확인 답변을 먼저 받지 않는다.
  10/29의 ‘첫 소재 공감만’ 결정은 이번 명시적 요청으로 대체한다. 아직 전인 일정의 예외는 유지한다.
- 카페에 가려다 귀찮아 안 나갔다면 그 상황을 받아주고 집에 있으면서 마음이 어땠는지 묻는다.
  사용자가 말하지 않은 ‘쉬기로 했다/편안했다’ 같은 행동·결정·감정은 지어내지 않도록 지시했다.
  G2의 첫 질문이 미래·생각·이유·의미·다른 소재 질문이거나 없으면 열린 감정 질문으로 대체한다.
- 다른 기억을 묻는 고정 질문 직후의 명확한 없음 답변은 AI로 되묻지 않고 초안을 만든 뒤
  기존 감정 탐색으로 넘어간다. 전체 답을 정확 매칭하므로 ‘없는데 산책했다’ 같은 새 내용,
  ‘없어요?’ 같은 질문, 다른 질문에 대한 없음은 종료하지 않는다. 5턴은 상한이다.
- 사용자 선택에 따라 일상 대화의 마지막 공감 / 감정 탐색 안내·질문은 두 말풍선으로 표시한다.
  기존 API text는 유지하고 FE의 diary_chat→emotion_exploration/await_branch 전환에서
  알려진 안내 경계로만 표시 버블을 나눈다. lastReply 원문·사용자 메시지·평소 바바 문단은 바꾸지 않는다.
- 공개 API DTO·필드·경로·상태·오류·인증, DB·snapshot·JPA·Flyway 정의 변경 없음.
- BE 집중 검사 `./gradlew test --tests '*RetrospectEngineTest' --tests '*PromptFactoryTest'
  --tests '*RetrospectApiIntegrationTest' --no-daemon` 성공(10초).
  없음 종료/오탐 방지·REST 저장·공감과 질문을 한 번에 보내기·5턴 상한·일정 예외를 확인했다.
  새 REST 검사에서 중간 초안을 일기 테이블에 기대한 1건은 실패했다. 실제 기존 설계상 초안은
  snapshot에 저장되고 회고 완료 시 일기 테이블에 저장하므로 snapshot 확인+테이블 empty로 수정했다.
  미래 질문을 받는 실제 경험 검사 1건은 첫 감정 질문에서 미래 표현을 제외하고 회귀를 추가해 고쳤다.
- FE 집중 `npm test -- --runInBand app/retro/__tests__/talk.test.tsx
  src/components/ds/__tests__/chatSentenceLines.test.tsx src/components/ds/__tests__/chatBubbleWidth.test.tsx`
  **3개 스위트·42건 통과**. 실제 두 단계 전환의 말풍선 분리, 원문 보존·고유 ID와 일반 문단 유지 확인.
  `npm run typecheck`, `npm run typecheck:expo-lab`, 변경 파일·문서 Prettier 및 양쪽 diff 검사 통과.
- 실제 Gemini 검사 `./gradlew test --tests '*GeminiDiaryChatAssistantRealIntegrationTest' --no-daemon`
  성공(11초). 공개 가상 5사례: 기존 구체적 공감, 아직 안 만남, 저녁 약속 전,
  아직 전+산책 경험, 새 카페 사례. 개인 대화·인증 정보는 전송·출력하지 않았다.
- 이번 추가 수정: FE `src/features/retro/model/retro.ts`, `app/retro/__tests__/talk.test.tsx`,
  `docs/DESIGN.md`, `docs/retro-chat-v2-plan.md`; BE `RetrospectEngine.java`, `DiaryTurn.java`,
  `PromptFactory.java`, `RetrospectEngineTest.java`, `PromptFactoryTest.java`,
  `RetrospectApiIntegrationTest.java`, `GeminiDiaryChatAssistantRealIntegrationTest.java`,
  `docs/retro-chat-v2-plan.md`, 이 문서. 직전 줄바꿈 변경과 아직 전 일정 변경은 보존했다.
  이번 추가 요청으로 새 파일·삭제 파일 없음.
- 최종 전체 BE `./gradlew clean check --no-daemon` 성공(1분 29초).
  86개 스위트 총 693건 중 **685개 통과 / 실패 0 / 오류 0 / 스킵 8**.
  PostgreSQL·기존 공개 API 계약 검사를 포함했다. 기본 실행의 Gemini 실제 호출 테스트 노드 7개와
  외부 challenge 검사 1개는 스킵했으며 위 별도 Gemini 실행에서 공개 가상 5사례는 통과했다.
- 최종 FE `npm test -- --runInBand` **131개 스위트·1,639건 통과**(19.8초).
  요청이 추가되기 전 시작한 BE 전체 검사 1회는 새 변경을 안정적으로 검증하기 위해 중단했으며,
  위 최종 전체 검사를 마지막 코드 변경 후 다시 실행해 통과했다.
- 독립 읽기 전용 리뷰 2회. 1차 Major 1건: 질문이 없는 같은 맥락의 두 문장도
  breakAfterEmpathy가 마지막 마침표에서 줄을 나눴다. 질문 없는 응답은 원문 유지하고
  전각 물음표도 질문 경계에 포함하도록 수정·회귀 검증했다. 2차 미해결 Critical/Major/Minor 없음.
  반려한 지적 없음. FE 폭 보정 후 실제 기기 자동 접힘·잘림·최종 두 버블 배치는 추가 확인이 필요하다.
- 최종 코드로 실기기에 연결된 로컬 서버를 재시작하고 `/actuator/health`의 `UP`을 확인했다.
  앱 화면 전달 서버도 `packager-status:running` 확인했다. 사용자 갤러리가 전면이라 화면을 강제로
  전환하거나 개인 채팅을 대신 보내지 않았다. USB·개발 서버·Expo가 켜져 있어야 한다.
- 결과: **PASS WITH WARNINGS**. 구현·필수 검증·리뷰 완료, 커밋·PR 검토 준비 상태다.
  다양한 실제 AI 표현과 실기기 최종 표시 검증 한계는 남는다. 커밋·푸시·원격 배포는 수행하지 않았다.

## 2026-10-30 채팅 줄바꿈을 문장이 아닌 맥락 단위로 변경

- 사용자 메시지는 입력한 문단·줄바꿈을 그대로 표시한다. 문장 부호마다 강제로 줄을 나누던
  FE 포맷터를 제거했다. 바바의 같은 맥락 문장도 이어 쓰고, 공감에서 질문으로 넘어가는 등
  서버가 나눈 맥락 경계의 줄바꿈을 그대로 표시한다.
- 말풍선 폭 측정 시 화면에서 접힌 글줄을 강제 줄바꿈으로 다시 조합하던 처리도 제거했다.
  폭만 보정하고 원문을 렌더링하므로 화면 폭에 따른 자동 접힘과 원문의 문단을 구분한다.
- G2 시스템 지시에 맥락별 문단 원칙을 명시했다. 장애 대체 공감의 경청·공감 두 문장은
  같은 문단으로 이어 쓴다. 기존 공감 전체와 다음 질문 사이 줄바꿈은 유지한다.
- 공개 API·전송·저장 원문·DB·인증 변경 없음. 직전 아직 전인 일정 예외와 공감 대화 정책은 보존했다.
- FE 집중 검사: `npm test -- --runInBand src/components/ds/__tests__/chatSentenceLines.test.tsx
  src/components/ds/__tests__/chatBubbleWidth.test.tsx expo-lab/src/retro/__tests__/RetroDiaryPreview.test.tsx
  expo-lab/src/retro/__tests__/BubbleComparisonPreview.test.tsx` **4개 스위트·21건 통과**.
  `npm run typecheck` 통과. 수동 줄바꿈·빈 문단·공백·문장 부호 보존, 폭 보정 뒤 원문·접근성,
  같은 맥락의 공감과 질문 문단을 확인했다.
- BE 집중 검사: `./gradlew test --tests '*RetrospectEngineTest' --tests '*PromptFactoryTest'
  --no-daemon` 성공(4초). 공감 두 문장을 이어 쓰고 질문만 다음 줄로 나누는 응답과 장애 대체 문단을 확인했다.
- 이번 수정: FE `ChatBubble.tsx`, `chatSentenceLines.test.tsx`, `chatBubbleWidth.test.tsx`,
  `docs/DESIGN.md`; 삭제 파일 `chatSentenceLines.ts`(호출부 없음). BE `RetrospectEngine.java`,
  `PromptFactory.java`, `RetrospectEngineTest.java`, `PromptFactoryTest.java`, 이 문서.
  새 파일 없음. 이전 미커밋 일정 예외 작업은 유지한다.
- 전체 검사·독립 리뷰·실기기 서버 재시작 결과는 아래에 기록한다. 커밋·푸시·원격 배포는 수행하지 않는다.

## 2026-10-30 아직 전인 일정에서 이전 경험으로 이어가기

- 사용자 실기기 피드백: ‘오늘 유나 약속 어땠어요?’에 ‘아직 안 만났어’라고 답하면
  ‘아직 만나기 전이시군요’에서 멈췄다. 실제 경험의 첫 공감 정책이 시간 정정에도 적용된 원인이었다.
- G2 내부 `eventPending`으로 아직 일어나지 않은 일정만 답한 경우를 구분한다.
  이때 상황을 받아주는 문장 뒤 ‘그럼 그전에는 어떤 일을 하셨어요?’를 묻는다.
  그 답변의 미래 사건·곁가지·의미·감정 슬롯은 반영하지 않아 이미 한 일로 잘못 기록하지 않는다.
- 실제 과거 경험도 함께 말했으면 그 경험을 추출해 기존 첫 공감 흐름을 따른다.
  일정 취소·못 만난 완료 사실에는 이 예외를 적용하지 않는다. 5턴 상한·안전·종료 우선순위와
  단답 체크인은 유지하되, 유효한 시간 정정 자체는 3턴 체크인으로 막지 않는다.
- AI 장애 시에도 공백을 제거한 ‘아직 안/못 만났어(요/습니다)’와 종결 부호만인 명확한 답은
  이전 경험 질문으로 이어간다. 복합 문장·다른 시간 표현은 AI 분류를 사용한다.
- 공개 API DTO·필드·경로·상태·오류·인증과 DB·snapshot·JPA·Flyway 정의 변경 없음.
  `eventPending`은 Gemini 내부 구조화 출력에만 추가했다. 기존 테스트 생성자에 기본 false를 전달했다.
- 집중 검증: `./gradlew test --tests '*RetrospectEngineTest' --tests '*PromptFactoryTest'
  --tests '*GeminiDiaryChatAssistantTest' --tests '*RetrospectApiIntegrationTest' --no-daemon` 성공(11초).
  첫 시간 정정→실제 경험의 첫 공감 복구, 미래 슬롯 미저장·기존 슬롯 보존, 3턴 체크인·5턴 상한,
  장애 대체 응답·내부 스키마와 REST 두 요청 사이 저장 상태를 검증했다.
- 실제 Gemini 검사: 키를 출력하지 않고 환경으로 전달해
  `./gradlew test --tests '*GeminiDiaryChatAssistantRealIntegrationTest' --no-daemon` 성공(10초).
  새 공개 가상 사례 3건(아직 안 만남·저녁 약속 전·아직 전+오전 산책)과 기존 구체적 공감 1건 통과.
- 수정 파일: BE `RetrospectEngine.java`, `DiaryTurn.java`, `GeminiResponseSchemas.java`,
  `PromptFactory.java`, `FakeAssistant.java`, `RetrospectEngineTest.java`,
  `GeminiDiaryChatAssistantRealIntegrationTest.java`, `GeminiDiaryChatAssistantTest.java`,
  `PromptFactoryTest.java`, `RetrospectApiIntegrationTest.java`,
  `RetrospectActionCardPersistenceIntegrationTest.java`, `docs/retro-chat-v2-plan.md`, 이 문서.
  FE `docs/DESIGN.md`, `docs/retro-chat-v2-plan.md` 설명만 갱신. 새 파일·삭제 파일 없음.
- FE 문서 Prettier와 양쪽 `git diff --check` 통과. Git 상태·전체 diff·untracked를 확인했다.
- 최종 전체 검증 `./gradlew clean check --no-daemon` 성공(1분 28초).
  86개 스위트 총 673건 중 **666개 통과 / 실패 0 / 오류 0 / 스킵 7**.
  PostgreSQL 저장·기존 공개 API 계약 검사를 포함했다. 스킵은 기본 실행의 실제 Gemini 호출
  테스트 노드 6개와 외부 challenge 데이터 검사 1개이며, 위 Gemini 별도 실행에서는 4사례 모두 통과했다.
- 독립 읽기 전용 리뷰 2회 완료. 미해결 Critical/Major/Minor 없음. 수정·반려한 지적 없음.
- 최종 코드로 실기기에 연결된 로컬 서버를 재시작하고 `/actuator/health`의 `UP`을 확인했다.
  Galaxy USB reverse 8080·8081 연결도 확인했다. 기존 앱은 다음 응답부터 새 정책을 사용한다.
- 결과: **PASS WITH WARNINGS**. 구현·필수 검증·독립 리뷰를 마쳐 커밋·PR 검토 준비 상태다.
  실제 Gemini 검사는 공개 가상 사례이며, 모든 자연어 표현 및 재시작 후 사용자의 최종 실기기 응답은
  추가 확인이 필요하다. USB·개발 서버·Expo가 켜져 있어야 한다.
  이번 변경은 아직 커밋·푸시·원격 배포하지 않았다.

## 2026-10-29 감정 확인 질문 추가 철회 및 기능별 커밋

- 사용자가 직전 변경을 되돌리도록 요청해 첫 소재 응답의 감정 확인 질문 추가만 철회했다.
  현재 정책은 **첫 소재에 구체적인 공감만 → 사용자의 후속 감정 답변 → 다시 공감하고 다른 기억 질문**이다.
  5턴 상한·두 사건 상한·안전·종료·단답 체크인과 이전 공감 품질/재시도 개선은 보존했다.
- 엔진의 질문 추가와 전용 질문 필터를 제거하고, G2 프롬프트·도메인 주석·정책 문서를 복구했다.
  기존 검사를 원래 정책으로 복구했으며 직전 추가한 14개 감정 질문 사례는 삭제하지 않고
  첫 응답에서 질문을 보내지 않는 역회귀 검사로 바꿨다.
- 이번 수정 파일: BE `RetrospectEngine.java`, `PromptFactory.java`, `DiaryTurn.java`,
  `RetrospectEngineTest.java`, `PromptFactoryTest.java`, `RetrospectApiIntegrationTest.java`,
  `GeminiDiaryChatAssistantRealIntegrationTest.java`, `docs/retro-chat-v2-plan.md`, 이 문서.
  FE `docs/DESIGN.md`, `docs/retro-chat-v2-plan.md`. 새 파일·삭제 파일 없음.
- 집중 검사 `./gradlew test --tests '*RetrospectEngineTest' --tests '*PromptFactoryTest'
  --tests '*RetrospectApiIntegrationTest' --no-daemon` 성공(9초).
  첫 감정/다른 질문 제거·공감 보존·후속 전환·저장 동작을 확인했다.
- API DTO·경로·상태·오류 코드·인증, snapshot·JPA·Flyway 정의 변경 없음.
- 사용자 후속 요청으로 검증 뒤 현재 FE/BE 변경을 기능별로 커밋한다. 푸시·원격 배포는 요청하지 않았다.
- 최종 전체 검사 `./gradlew clean check --no-daemon` 성공(1분 26초).
  86개 스위트, 총 663건 중 **658개 통과 / 실패 0 / 오류 0 / 스킵 5**.
  PostgreSQL 통합·기존 API 계약 검사를 포함한다. Gemini 실제 호출 4건과 외부 challenge 검사 1건은
  기본 실행에서 스킵했다. 이번 롤백에서 Gemini 실제 호출을 새로 실행하지 않았다.
- FE 전체 `npm test -- --runInBand` **131개 스위트·1,635건 통과**.
  `npm run typecheck`, `npm run typecheck:expo-lab`, 두 정책 문서 Prettier 검사와 양쪽
  `git diff --check` 통과. FE 실행 코드는 이번 롤백으로 바뀌지 않았다.
- 독립 읽기 전용 리뷰 2회 완료. 미해결 Critical/Major/Minor 및 반려한 지적 없음.
  롤백 전 파일별 사본과 비교해 감정 질문 추가만 철회했는지 검토했다.
- 로컬 DB가 중지돼 첫 서버 재시작은 연결 거부로 실패했다. 기존 컨테이너·데이터를 유지한 채
  DB를 다시 실행하고 서버 재시작 후 `/actuator/health`의 `UP`을 확인했다.
  실기기는 USB·개발 서버·Expo가 켜진 상태에서 다음 응답부터 복구된 정책을 사용한다.
- 결과: **PASS WITH WARNINGS**. 구현·필수 검증·리뷰 완료. 실제 AI 문장 품질의 다양한 사례는
  이 롤백에서 추가 검증하지 않았다. 원격 배포·푸시는 수행하지 않는다.
- 기능별 커밋 구성: FE 상태바 → 다이어리 화면·스티커 선택지 → Lab 비교 미리보기,
  BE AI 일시 장애 재시도·출력 검증 → 5턴 상한·소재별 공감 대화 흐름. 기존 미커밋 작업을 함께 정리한다.
- 아래 감정 확인 질문 섹션은 철회된 실험의 이력이다.

## 2026-10-29 소재 첫 공감 뒤 감정 확인 질문

- 사용자 추가 요청으로 앞선 ‘첫 응답은 공감만’ 정책을 대체했다. 첫 소재 답변에는 상황·표현한
  마음을 짚는 공감 1~2문장 뒤 감정 확인 질문 하나를 보낸다. 맥락이 분명하면 ‘재밌으셨나요?’,
  ‘화가 나셨나요?’처럼 확인하고, 감정을 단정하지 않는다. 감정 확인에 답하면 다시 공감하고
  다른 기억에 남는 일을 묻는다. 다음 소재에서도 같은 흐름을 적용한다.
- 기존 G2의 `question`·`empathy`를 사용한다. 질문 필드에 공감이 섞여도 중복 없이 감정 질문
  하나를 고른다. 생각·의미·이유·다른 사건 질문이나 질문 누락·AI 장애는 열린 질문
  ‘그때 어떤 기분이 드셨나요?’로 대체한다. 공감 필드의 질문 제거와 맥락 공감은 유지한다.
- 5번째 사용자 답변의 종료 전환, 안전·사용자 종료·단답 체크인·두 사건 상한은 유지한다.
  API DTO·필드·경로·상태·오류 코드, 인증, snapshot·JPA·Flyway 소스 변경 없음.
- 집중 검증: `./gradlew test --tests '*RetrospectEngineTest' --tests '*PromptFactoryTest'
  --tests '*RetrospectApiIntegrationTest' --no-daemon` 성공. 첫 실행의 실패 1건은 정상 AI 질문의
  어미를 fallback 어미로 기대한 검사였으며, 실제 정상 질문을 보존하도록 기대값을 고쳤다.
  긍정·부정·열린 감정 질문, 공감/질문 분리·중복 방지, 생각·이유·의미·새 소재 질문 대체,
  두 번째 소재·장애·후속 전환과 REST 저장을 검증했다.
- 실제 Gemini: 키를 출력하지 않고 환경으로 전달한
  `./gradlew test --tests '*GeminiDiaryChatAssistantRealIntegrationTest' --no-daemon` 성공(4초).
  공개된 가상 생일 사례에서 상황을 짚는 공감과 감정 확인 질문을 확인했다.
- 독립 읽기 전용 리뷰 2회. 1차 Major 1건: ‘무엇 때문에 화가 나셨나요?’,
  ‘어떤 부분이 마음에 남았나요?’가 감정 키워드만으로 통과했다. 이유·의미 표현을 제외하고
  두 회귀 사례를 추가했다. 2차 미해결 Critical/Major/Minor 없음. 반려한 지적 없음.
- 이번 수정: BE `RetrospectEngine.java`, `PromptFactory.java`, `DiaryTurn.java`,
  `RetrospectEngineTest.java`, `PromptFactoryTest.java`, `RetrospectApiIntegrationTest.java`,
  `GeminiDiaryChatAssistantRealIntegrationTest.java`, `docs/retro-chat-v2-plan.md`, 이 문서.
  FE는 `docs/DESIGN.md`, `docs/retro-chat-v2-plan.md` 설명만 갱신했다.
  이번 요청으로 생성·삭제한 파일 없음. 기존 미커밋 변경·실기기 데이터는 보존했다.
- 양쪽 Git 상태·tracked/untracked 변경과 `git diff --check` 확인. FE 두 문서 Prettier 검사 통과.
- 최종 전체 검증: `./gradlew clean check --no-daemon` 성공(1분 27초).
  86개 스위트 총 663건 중 **658개 통과 / 실패 0 / 오류 0 / 스킵 5**.
  PostgreSQL 통합 검증을 포함한다. 스킵은 기본 실행에서 Gemini 실제 호출 4건과 외부 challenge
  데이터 검사 1건이며, 이번 Gemini 실제 호출 검사는 별도 실행에서 통과했다.
- 결과: **PASS WITH WARNINGS**. 구현·계약 보존·저장·필수 검증과 독립 리뷰를 완료했다.
  커밋·PR 검토 준비 상태다. 다양한 실제 응답의 문장 품질과 질문 의미를 모든 경우에 완전히
  분류하는지는 보장하지 않는다. 커밋·푸시·PR·병합·원격 배포는 수행하지 않았다.
- 최종 코드로 실기기에 연결된 로컬 서버를 재시작했고 `/actuator/health`의 `UP`을 확인했다.
  Galaxy USB reverse 8080·8081도 확인했다. 앱을 재설치할 필요 없이 새 응답부터 반영된다.
  USB·로컬 서버·Expo를 실행해 둔 상태에서 테스트한다.

## 2026-10-29 실기기 연결 및 상황을 짚는 공감

- 연결된 Galaxy 개발 앱의 API를 이 컴퓨터의 `http://127.0.0.1:8080`으로 연결했다.
  Expo는 `EXPO_PUBLIC_API_BASE_URL=http://127.0.0.1:8080` 프로세스 환경으로 실행하며 `.env`는
  수정하지 않았다. USB reverse 8080·8081과 Android manifest의 API 주소를 확인했다.
- 로컬 서버는 `local` 프로필·루프백 주소만 사용한다. 기존 `momentory-postgres-data` 볼륨을
  보존하며 PostgreSQL은 `127.0.0.1:15432`에 바인딩했다. 다른 로컬 서비스와 원격 개발·운영 서버는
  변경하지 않았다. 기존 로컬 DB에 저장소의 V24~V32 마이그레이션이 정상 적용됐다.
- 실기기 요청에서 Gemini의 일시적 503이 발생해 첫 공감이 ‘그랬군요’로만 끝나는 것을 확인했다.
  G2는 요청당 8초·일시 장애 시 1초 뒤 한 번만 재시도한다. 안전 차단·유출·잘못된 요청 같은
  재시도 불가 응답은 반복하지 않는다. G2 읽기 대기를 제한한다. 회고 API의 앱 한도는 45초이며 마지막 턴의 G1·G4 시간은 별도다.
- Gemini 내부 출력에서 `empathy`를 필수로 받으며, 사용자가 말한 장면·행동·직접 표현한 감정을
  짚는 1~2문장을 요구한다. 감정이 없는 발화에 감정을 지어내지 않는다.
- 엔진은 공감을 첫 문장으로 무조건 자르지 않는다. 두 번째 공감도 살리고, 짧은 맞장구만인 문장은
  제외한다. 질문은 보내지 않는다. 이전 출력의 `question`에만 담긴 공감도 질문 앞부분에서 복구한다.
  AI가 끝내 실패하면 사용자 발화를 60 code point 이내로 짚고 경청하는 문장을 붙인다.
- 첫 소재에는 공감만, 후속 감정에는 공감과 다른 기억 질문, 5턴 상한·안전·종료 정책은 유지한다.
- 질문표 없이 ‘들려주세요/말씀해 주세요’로 끝나는 요청도 공감으로 복구하지 않도록 차단했다.

### 검증 및 전달 결과 — 실기기 공감

- 결과: **PASS WITH WARNINGS**. `feat/retro-diary-five-turns`에서 구현·검증 완료.
  커밋·PR 검토 준비 상태이며 커밋·푸시·PR·병합·원격 배포는 수행하지 않았다.
- 집중 검증: `./gradlew test --tests '*RetrospectEngineTest' --tests '*PromptFactoryTest'
  --tests '*GeminiApiClientTest' --tests '*GeminiDiaryChatAssistantTest'
  --tests '*RetrospectApiIntegrationTest' --no-daemon` 성공.
  최종 요청형 문장 차단 후 엔진·G2 어댑터 집중 검사도 다시 통과했다.
- 실제 Gemini 검사: 로컬 키를 출력하지 않고 환경으로 전달해
  `./gradlew test --tests '*GeminiDiaryChatAssistantRealIntegrationTest' --no-daemon` 성공.
  가상의 친구 생일 사례에서 상황·감정을 짚는 공감과 질문 없는 응답을 확인했다.
- 최종 전체 검증: `./gradlew clean check --no-daemon` **성공** (1분 29초).
  86개 스위트, 총 649건 중 **644개 통과 / 실패 0 / 오류 0 / 스킵 5**.
  기본 실행의 스킵은 Gemini 실호출 4건과 외부 challenge 데이터 검사 1건이며,
  새 Gemini 실호출 1건은 위의 별도 실행에서 통과했다. PostgreSQL 통합 검증을 포함했다.
- 별도 읽기 전용 리뷰 3회 완료. 1차 Minor 1건(앱 시간 제한·G2 대기 범위 설명)을 수정했고,
  마지막 리뷰에 미해결 Critical/Major/Minor 없음. 반려한 지적 없음.
  첫 응답의 구체적인 두 문장, 짧은 맞장구 제거, 이전 출력 복구, 질문 없는 요청형 문장 차단,
  장애·후속 감정 대체 응답, 재시도 가능 여부·횟수·필수 empathy 출력 검사를 추가했다.
- 최종 코드로 로컬 서버를 재시작해 `/actuator/health`의 `UP`을 확인했다.
  Galaxy의 USB reverse 8080·8081 연결과 앱 실행 상태도 확인했다.
  실기기는 이 컴퓨터의 서버를 사용하므로 USB·로컬 서버·Expo가 실행 중이어야 한다.
- API DTO·경로·상태·오류·인증과 JPA·Flyway 소스·DB 스키마 정의 변경 없음.
  LLM 내부 출력 스키마에만 `empathy` 필수를 추가했다. 기존 계약·저장 검사는 전체 검증에 포함했다.
- 이번 공감 수정 파일: `RetrospectEngine.java`, `DiaryTurn.java`, `GeminiDiaryChatAssistant.java`,
  `GeminiResponseSchemas.java`, `PromptFactory.java`, `RetrospectEngineTest.java`, `PromptFactoryTest.java`,
  `docs/retro-chat-v2-plan.md`, 이 문서. 새 파일은 `GeminiDiaryChatAssistantTest.java`,
  `GeminiDiaryChatAssistantRealIntegrationTest.java`. FE는 `docs/DESIGN.md`의 설명만 갱신했다.
  삭제 파일 없음. 기존 디자인·5턴·단일 선택 변경을 보존했다.
- 양쪽 저장소의 Git 상태·tracked/untracked 변경 확인 및 `git diff --check` 통과.
  FE 문서 Prettier 검사 통과.
- 남은 제한: 실제 AI 검사는 가상 사례 한 건이며 모든 상황의 문장 품질을 보장하지 않는다.
  외부 장애가 지속될 때 전체 대화 단계의 지연 시간은 별도 실측하지 않았다.
  원격 개발·운영 서버에는 배포하지 않았다.

## 2026-10-29 소재 첫 발화에는 공감만 하기 — 최종 정책

- 사용자의 구체화 요청으로 앞선 즉시 전환 정책을 대체했다. 각 소재의 첫 발화에는 질문 없이
  공감만 한다. 첫 발화에 감정이 함께 있어도 다른 소재로 유도하지 않는다.
- 공감 뒤 사용자가 감정만 답하면 다시 짧게 공감하고 다른 기억에 남는 일을 묻는다.
  다른 사건을 새로 꺼내면 소재 턴이 초기화되므로 그 사건의 첫 응답도 공감만 한다.
- 기존 `topicTurn` 저장 값을 사용한다. API·스냅샷 필드·DB 매핑·스키마·마이그레이션 변경 없음.
  5턴 상한·안전·사용자 종료·단답 체크인·두 사건 상한을 유지한다.
- AI 공감 필드에 질문이 섞이거나 AI가 실패하면 질문 없는 공감으로 대체한다.
  검사 중 발견한 ‘취소됐어요/잘됐어요’의 ‘됐어요’ 종료 오탐도 고쳤으며, 실제 ‘됐어요’ 종료는 유지한다.
- 정상 새 소재가 짧은 경우에는 단답 체크인보다 첫 공감을 우선한다. AI가 무관·모호한 답변으로
  판정한 경우에는 기존 단답 체크인을 유지한다.

### 검증 및 전달 결과 — 소재 첫 공감

- 결과: **PASS WITH WARNINGS**. 기능 브랜치 `feat/retro-diary-five-turns`에서 구현·검증 완료.
  커밋·PR 검토 준비 상태이며 커밋·푸시·PR·병합·배포는 수행하지 않았다.
- 최종 집중 검증: `./gradlew test --tests '*RetrospectEngineTest' --tests '*RetrospectStateTest'
  --tests '*PromptFactoryTest' --tests '*RetrospectStateCodecTest' --tests '*RetrospectApiIntegrationTest'
  --no-daemon` 성공. 첫 실행에서 취소 발화의 종료 오탐과 변경된 정책에 맞지 않는 codec 기대값이
  드러나 수정했다. 첫 발화·후속 감정·두 번째 소재·짧은 정상 소재와 모호한 답변·AI 실패·질문이
  섞인 공감·종료 요청·기존 저장 상태를 검증했다. REST 두 요청 사이에 `topicTurn=1→2`가 보존된다.
- 최종 전체 검증: `./gradlew clean check --no-daemon` **성공** (1분 25초).
  84개 스위트, 총 637건 중 **633개 통과 / 실패 0 / 오류 0 / 스킵 4**.
  스킵은 실제 Gemini 호출 3건과 외부 challenge 데이터가 필요한 프롬프트 검사 1건이다.
  Docker PostgreSQL 통합 검증을 포함했다.
- 별도 읽기 전용 리뷰 2회 완료. 1차 Major 1건(3~4턴에서 짧은 정상 새 소재가 첫 공감보다
  단답 체크인으로 먼저 빠짐)을 고치고 정상·모호 입력을 구분하는 두 회귀 테스트를 추가했다.
  2차 리뷰에는 미해결 Critical/Major/Minor 없음. 반려한 지적 없음.
- API·OpenAPI DTO·경로·메서드·상태·오류 코드와 JPA·Flyway·DB 스키마 변경 없음.
  기존 snapshot의 `topicTurn`으로 제어하므로 새 상태 필드나 데이터 이관이 필요하지 않다.
  기존 계약·저장 검사도 전체 검증에서 통과했다.
- 이번 요청 수정 파일(BE): `RetrospectEngine.java`, `RetrospectState.java`, `DiaryTurn.java`,
  `PromptFactory.java`, `RetrospectEngineTest.java`, `RetrospectStateTest.java`, `PromptFactoryTest.java`,
  `RetrospectStateCodecTest.java`, `RetrospectApiIntegrationTest.java`, `docs/retro-chat-v2-plan.md`, 이 문서.
  FE는 `docs/DESIGN.md`, `docs/retro-chat-v2-plan.md`의 정책 설명만 갱신했다.
  이번 요청으로 만든 새 파일·삭제 파일 없음. 이전 디자인·5턴·단일 감정/욕구 선택 작업을 보존했다.
- Git 상태·tracked/untracked 검사 및 양쪽 `git diff --check` 통과. FE 문서 Prettier 검사 통과.
- 남은 검증: 실제 Gemini의 소재·감정 판정과 공감 문장 품질. 새 서버 정책은 배포 전이며 원격
  API를 쓰는 실기기에는 아직 반영되지 않는다. 이 제한을 포함해 병합·출시 전에 실제 응답을 확인한다.

## 2026-10-29 감정 확인 후 소재 전환

- 같은 `feat/retro-diary-five-turns` 브랜치에서 사용자 추가 요청을 반영했다.
- 감정과 의미를 모두 기다리던 소재 전환 조건을 감정 확인만으로 바꿨다. AI가 생각·의미를
  묻는 질문을 내더라도 엔진이 짧은 공감과 다른 일 질문으로 바꾼다. 의미 수집 진행 표시는
  프롬프트에서 제거했으며, 의미 슬롯은 자발적 발화 추출과 기존 세션 호환에만 남긴다.
- 새 사건을 추가하는 것은 기존 두 사건 상한 안에서만 허용한다. 5번째 답변에서는 기존 감정 탐색
  전환이 먼저 적용되고, 사용자 종료·단답 체크인·안전 정책도 유지한다. 짧더라도 감정을 답한
  정상 발화는 단답 체크인 대신 소재 전환으로 이어진다.
- API 필드·경로·오류 코드와 DB 매핑·스키마·마이그레이션 변경 없음. 커밋·푸시·배포 전이다.

### 검증 및 전달 결과 — 감정 확인 후 소재 전환

- 결과: **PASS WITH WARNINGS**. 구현·회귀 검증을 완료했고 커밋·PR 검토 준비 상태다.
  실제 Gemini의 감정 분류·공감 문장 품질은 검증하지 않았다. 서버 배포 전이므로 원격 API를
  사용하는 실기기에는 아직 적용되지 않는다. 커밋·푸시·PR·병합·배포는 수행하지 않았다.
- 집중 검증: `./gradlew test --tests '*RetrospectEngineTest' --tests '*RetrospectStateTest'
  --tests '*PromptFactoryTest' --tests '*RetrospectStateCodecTest' --tests '*RetrospectApiIntegrationTest'
  --no-daemon` 성공. 긍정·부정 감정, 사건 없는 감정, 짧은 감정 답변, 두 사건 상한, 감정 없는
  답변, AI 실패 시 전환 및 REST 세션 저장을 검증했다.
- 최종 전체 검증: `./gradlew clean check --no-daemon` **성공** (1분 25초).
  84개 스위트, 총 625건 중 **621개 통과 / 실패 0 / 오류 0 / 스킵 4**.
  PostgreSQL을 사용하는 REST 통합 테스트 10개는 모두 실행·통과했다.
  스킵은 실제 Gemini 호출 3건과 외부 challenge 세트가 필요한 프롬프트 검사 1건이다.
- 독립 읽기 전용 리뷰 1회 완료. Critical/Major/Minor 지적 없음. 수정하거나 반려한 지적 없음.
  기존 사건 전환 시 감정 상태 초기화, 정상 감정 답변만 단답 체크인을 건너뛰는 조건,
  5턴 상한·종료·안전 정책 우선순위와 기존 의미 슬롯 호환을 확인했다.
- API DTO·필드·경로·오류 코드, JPA 매핑·DB 스키마·Flyway 변경이 없어 계약 변경이나
  마이그레이션은 필요하지 않다. 기존 계약·영속화 검증을 포함한 전체 검사에 통과했다.
- 이번 요청 수정 파일: BE `RetrospectEngine.java`, `RetrospectState.java`, `DiaryTurn.java`,
  `PromptFactory.java`, `RetrospectEngineTest.java`, `RetrospectStateTest.java`, `PromptFactoryTest.java`,
  `RetrospectApiIntegrationTest.java`, `docs/retro-chat-v2-plan.md`, 이 문서.
  FE는 `docs/DESIGN.md`, `docs/retro-chat-v2-plan.md`의 대화 정책을 갱신했다.
  이번 요청으로 만든 새 파일·삭제 파일 없음. 앞선 화면·5턴 상한 작업은 그대로 유지했다.
- 양쪽 저장소의 Git 상태·tracked/untracked 변경 확인 및 `git diff --check` 통과.
  FE 문서 Prettier 검사 통과. 이번 요청은 FE 실행 코드를 바꾸지 않았다.

## 2026-10-29 일기 작성 대화 상한 변경

- 사용자 요청으로 `DIARY_MAX_TURNS`를 6에서 5로 줄였다. 최초 질문은 턴 수에 포함하지 않는다.
- 정상 답변 4턴까지 일기 대화를 유지하고 5번째 답변에서 감정 탐색으로 넘어간다. 사건이 둘이면
  기존 사건 선택을 거치고, 사건을 추출하지 못하면 기존대로 일기를 마무리한다.
- 사용자 종료·단답 체크인·안전 정책, API 필드·경로와 DB 스키마는 유지한다.
- 엔진 경계값·슬롯 미수집·도메인 소진·G2 프롬프트 및 REST 세션 저장 검증을 갱신했다.

### 검증 및 전달 결과 (2026-10-29)

- 결과: **PASS WITH WARNINGS**. BE 브랜치 `feat/retro-diary-five-turns`, FE 브랜치
  `feat/retro-diary-stickers`. 커밋·푸시·PR·병합·배포는 수행하지 않았다. 커밋·PR 검토 준비 상태다.
- 적용 절차: `.agents/skills/momentory-backend-delivery/SKILL.md`. 독립 읽기 전용 리뷰 2회.
  1차 리뷰의 Major 1건(FE 회고 화면이 스택에 남아 일기 확인 화면에서도 밝은 상태바가 유지됨)을
  현재 라우트 기반 `RouteStatusBar`로 고치고 회귀 테스트를 추가했다. 2차 리뷰에는 미해결
  Critical/Major/Minor가 없었다. 반려한 지적은 없다.
- 집중 검증: `./gradlew test --tests '*RetrospectEngineTest' --tests '*RetrospectStateTest'
  --tests '*PromptFactoryTest' --tests '*RetrospectStateCodecTest' --tests '*RetrospectApiIntegrationTest'
  --no-daemon` 성공. 처음에는 Docker가 꺼져 통합 테스트가 스킵됐으므로 Docker를 실행한 뒤 전체 검증했다.
- 최종 검증: `./gradlew clean check --no-daemon` **성공**. 84개 스위트, **611개 통과 / 실패 0 / 스킵 4**.
  REST 통합 테스트 9개는 모두 실행·통과했고, 1~4턴 유지·5턴 전환·저장된 `diaryTurn=5`를 확인했다.
  스킵은 실제 Gemini 호출 3건과 외부 challenge 세트가 필요한 프롬프트 검사 1건이다.
- FE 최종 검증: `npm run typecheck`, `npm run typecheck:lab`, `npm run typecheck:expo-lab`,
  `npm run lint`, `npm run format:check`, `npm test -- --ci --runInBand` 전부 성공.
  129개 스위트, **1,619개 테스트 통과**. 대화 영역 탭으로 키보드를 내려도 초안이 유지되는 회귀 검증 포함.
- 양쪽 저장소의 전체 tracked/untracked 변경 확인 및 `git diff --check` 통과.
  FE의 CI 시크릿 파일 추적 검사도 통과했다. API DTO·필드·경로·오류 코드, DB 매핑·스키마·Flyway는
  바뀌지 않아 별도 계약 변경이나 마이그레이션은 없다. 실제 PostgreSQL을 사용하는 전체 검사도 통과했다.
- 변경 파일(BE, 모두 수정): `RetrospectState.java`, `RetrospectEngine.java`, `Phase.java`,
  `DiaryTurn.java`, `PromptFactory.java`, `RetrospectEngineTest.java`, `RetrospectStateTest.java`,
  `PromptFactoryTest.java`, `RetrospectApiIntegrationTest.java`,
  `RetrospectActionCardPersistenceIntegrationTest.java`, `docs/retro-chat-v2-plan.md`, 이 문서.
  새 파일·삭제 파일 없음. FE는 C2·C4 화면, 공용 대화 컴포넌트, 바바·배경 자산, Lab 후보·미리보기,
  라우트 상태바 및 회귀 테스트·디자인 문서에 반영했다.
- 남은 검증: iPhone 시뮬레이터는 실행 중이나 `kr.momentory.app`이 설치돼 있지 않았다.
  실제 iOS/Android 키보드 리사이즈·바바 배치와 실제 Gemini 응답 품질은 확인하지 않았다.
  Lab 키보드는 모형이며 네이티브 검증을 대신하지 않는다. 병합·출시 전에 개발 앱에서 확인한다.

아래는 이전 작업 이력이다.

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


## 2026-10-14 회고 채팅 개선

- 브랜치: `feat/retro-chat-topic-selection` (최신 origin/main 기준).
- 직접 질문/힌트 분리, 분야별 후보 우선순위, 힌트 시작 기록과 폴백 일기 보호 구현.
- 소재별 턴·감정·의미 추적과 리셋, G2 전환 지시, 6턴 고정 종료 구현.
- 기존 분기 뒤 추출 사건 선택 추가(한 사건도 표시, 사건 없으면 일기 완료).
- 사건 ID에 연결된 감정 후보, 선택 요약을 질문·바람·행동·카드가 공통 사용.
- 스냅샷 신규 필드의 구버전 기본값 및 기존 탐색 세션 대표 사건 복원 구현.
- 일기 프롬프트를 전체 6문장 내외로 변경하고 잡일·허구로 분량 채우기 금지.
- 관련 엔진·도메인·프롬프트·코덱 회귀 테스트 통과.
- 검증: `./gradlew test --tests '*RetrospectEngineTest' --tests '*RetrospectStateTest' --tests '*RetrospectStateCodecTest' --tests '*PromptFactoryTest' --no-daemon` 통과.
- 전체 검증: `./gradlew clean check --no-daemon` 최종 통과. 이전 실행에서 기존 PickTopicTest 3개가
  옛 정책을 기대해 실패했으며, 새 정책 단언과 네 분야·여섯 사건 키워드 회귀 사례로 갱신 후 재검증했다.
- `git diff --check` 통과. API 요청/응답 필드·phase·엔드포인트 및 DB 스키마 변경 없음.
- 변경 파일: 엔진, State/Snapshot/Codec, DiaryTurn 주석, PromptFactory, PickTopicTest,
  Engine/State/Codec/PromptFactory 테스트, 카드 영속화 통합 테스트, 계획·진행 문서 (14개).
  생성·삭제 파일 없음. 기존 untracked `eval/` 보존. 커밋·푸시하지 않음.
- 검증 제한: Docker 없는 환경의 통합 테스트 스킵. 실제 Gemini 소재 문자열 재사용·일기 분량은
  라이브 호출로 확인하지 않았다. 결과는 PASS WITH WARNINGS이며 커밋 검토 가능, 병합 전 통합 검증 필요.

- 최종 검사 집계: 총 516개, 통과 279개, 실패 0개, 스킵 237개.

- 별도 read-only 리뷰어 2회 검토 완료: 미해결 Critical/Major/Minor 없음. 오래된 주석을 정정했고 반려한 리뷰 지적 없음.
