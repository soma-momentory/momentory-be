# 욕구별 행동 추천

감정 탐색의 마지막 턴은 AI의 자유 생성 대신, 사용자가 확인한 욕구에 연결된 행동 후보 중에서 고른다.

- 연결표: [01. 욕구별 행동 연결 — 50개 욕구](https://app.notion.com/p/405b5a896e428198ab9adb01dafd2285)
- 제목·실행법·연구 분량·한계·출처: [02. 통합 행동 사전](https://app.notion.com/p/405b5a896e4281ca9d8fccf91e4d24cf)
- 스냅샷 날짜: 2026-10-26
- 배포 리소스: `src/main/resources/retrospect/need-actions.json`
- 욕구 50개, 행동 사전 55개, 욕구–행동 연결 195개.

## 선정과 저장

1. 기존 흐름대로 사건·감정·바람을 확인한다.
2. `NeedActionCatalog`가 확인된 욕구의 연결표에서 후보를 모은다.
3. AI에 선택 사건, 확인 감정, 욕구, 연결 근거의 한계, 후보의 제목·실행법·연구 분량·조건을 보낸다.
4. AI는 적합한 후보의 `actionIds`를 최대 3개 반환한다. 새 행동이나 새 문구를 만들지 않는다.
5. 서버가 욕구별 허용 ID와 대조하고, 목록 밖·다른 욕구·중복 응답을 제거한다.
   화면에는 AI가 쓴 문구 대신 스냅샷의 승인된 제목을 그대로 보낸다.
6. 사용자가 고른 제목 또는 직접 입력한 행동을 기존 바람 카드에 저장한다.

모델이 아무것도 고르지 않거나 호출에 실패하면 추천 후보는 빈 목록이다. 일반적인 메모·휴식 행동으로
대체하지 않는다. 기존 입력창에서 직접 적거나, 행동을 정하지 않고 회고를 마칠 수 있다.
직접 입력한 **사용자 행동**은 추천 후보 목록으로 제한하지 않는다.

욕구를 직접 입력할 때 고정 욕구 단어와 정확히 일치하면(앞뒤 공백 제외) 같은 연결표를 사용한다.
욕구를 확인하지 않았거나 목록 밖 자유 표현만 입력한 경우에는 연결을 추측하지 않고 행동 직접 입력을 허용한다.

## 목록과 연구 조건

- `A05·A14·A22·A33·A53·A57·A60`은 제외된 행동이므로 후보에 포함하지 않는다.
- 사전에 있지만 연결표에 없는 `A10·A20·A27·A31`에는 임의의 욕구 연결을 추가하지 않았다.
  실제 욕구별 후보에 포함되는 고유 행동은 51개다.
- 노션의 ‘직접·근접·해석’, 예비 표시, 욕구별 주의 문구, 출처를 리소스에 보존한다.
  연결이 있다는 사실만으로 해당 욕구의 충족이 검증됐다고 설명하지 않는다.
- A32의 제목에는 시간이 없지만 연구 절차의 약 45분과 축약의 한계를 선정 프롬프트에 포함한다.
  다른 행동도 제목과 별도로 실행법·연구 분량·조건을 함께 전달한다.
- 상대의 동의·비용·장소·도구 등 조건이 맞지 않으면 후보 수를 억지로 채우지 않도록 지시한다.
  이 맥락 판단은 AI에 맡기며, 서버는 후보 ID와 욕구 연결·중복·제목을 검증한다.

## 배포와 갱신

요청마다 Notion을 호출하지 않는다. 리소스는 배포에 포함된 스냅샷이므로 이후 노션 수정은 자동 반영되지 않는다.
갱신할 때 두 원본 페이지를 함께 확인해 제목·연결·연구 조건을 반영하고, 스냅샷 날짜와 테스트를 갱신한다.
앱 시작 시 고정 욕구 정의, 중복 ID, 연결된 행동과 제목을 검증한다.

이번 변경은 내부 추천 방식과 Gemini 구조화 응답만 바꾼다. 외부 API의 경로·요청/응답 필드·상태 코드,
기존 카드·일기 저장 방식과 DB 스키마는 유지한다.

## 검증

```bash
./gradlew test --tests '*NeedActionCatalogTest' --tests '*RetrospectDomainConfigurationTest' --tests '*GeminiExplorationAssistantTest' --tests '*GeminiApiClientTest' --tests '*PromptFactoryTest' --tests '*RetrospectEngineTest' --no-daemon
./gradlew clean check --no-daemon
```

테스트는 배포 리소스 로딩과 Spring 빈 구성, 욕구별 후보 제한, 제외 행동·중복·잘못된 ID 제거,
승인 제목 유지, Gemini 응답 스키마, 실패 시 직접 입력·행동 미정 종료를 검증한다.
실제 Gemini의 상황 적합성은 모의 응답 테스트와 별도로 확인해야 한다.

## 이번 변경의 검증 기록 — 2026-10-26

- 결과: **PASS WITH WARNINGS**. 핵심 요구사항과 실행한 검사는 통과했다.
- 위의 대상 테스트 명령: 78개 중 77개 통과, challenge 자료가 필요한 기존 1개 건너뜀.
- `./gradlew clean check --no-daemon`: **BUILD SUCCESSFUL**.
  총 572개 중 317개 통과, 실패·오류 0개, 환경·자료 의존 검사 255개 건너뜀.
- `git status --short`로 기존 작업과 새 파일을 확인했고, `git diff --check` 통과.
- 별도 읽기 전용 리뷰어가 변경 코드·테스트·문서와 원본 노션을 검토했다.
  55개 행동과 195개 연결의 일치도 독립 확인했으며 Critical/Major/Minor 지적은 없었다.
  리뷰에 따른 수정·반려 지적 없음.
- 외부 API 계약과 DB 스키마 변경이 없어 신규 API/OpenAPI·마이그레이션 게이트는 적용되지 않는다.
  기존 계약·카드 저장 통합 테스트는 Docker 환경에서 실행되지 않아 런타임 검증을 완료했다고 주장하지 않는다.
- 실제 Gemini의 새 응답 형식 수용과 상황별 선정 품질은 라이브 호출로 확인하지 않았다.
- 기존 `eval/` 파일 39개의 해시가 작업 전과 같음을 확인했다.
- 변경은 로컬 작업 트리에 있으며 커밋 검토 가능 상태다. 이번 구현을 커밋·푸시하거나 배포하지 않았다.

### 추가 파일 7개

- `src/main/java/com/momentory/retrospect/domain/NeedActionCatalog.java`
- `src/main/resources/retrospect/need-actions.json`
- `src/test/java/com/momentory/retrospect/NeedActionCatalogFixture.java`
- `src/test/java/com/momentory/retrospect/domain/NeedActionCatalogTest.java`
- `src/test/java/com/momentory/retrospect/infrastructure/RetrospectDomainConfigurationTest.java`
- `src/test/java/com/momentory/retrospect/infrastructure/ai/GeminiExplorationAssistantTest.java`
- `docs/need-action-recommendation.md`

### 수정 파일 12개

- `src/main/java/com/momentory/retrospect/application/RetrospectEngine.java`
- `src/main/java/com/momentory/retrospect/domain/assistant/ExplorationAssistant.java`
- `src/main/java/com/momentory/retrospect/infrastructure/RetrospectDomainConfiguration.java`
- `src/main/java/com/momentory/retrospect/infrastructure/ai/GeminiExplorationAssistant.java`
- `src/main/java/com/momentory/retrospect/infrastructure/ai/GeminiResponseSchemas.java`
- `src/main/java/com/momentory/retrospect/infrastructure/ai/GeminiStructuredOutputs.java`
- `src/main/java/com/momentory/retrospect/infrastructure/ai/PromptFactory.java`
- `src/test/java/com/momentory/retrospect/application/RetrospectEngineTest.java`
- `src/test/java/com/momentory/retrospect/infrastructure/ai/GeminiApiClientTest.java`
- `src/test/java/com/momentory/retrospect/infrastructure/ai/PromptFactoryTest.java`
- `src/test/java/com/momentory/retrospect/presentation/RetrospectActionCardPersistenceIntegrationTest.java`
- `src/test/java/com/momentory/retrospect/presentation/RetrospectApiIntegrationTest.java`

삭제 파일 없음.
