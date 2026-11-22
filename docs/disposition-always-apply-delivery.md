# 세 성향을 매 회고 시작에 반영

2026-11-22 사용자 요구를 기준으로 구현했다. Notion의 기존 조건부 반영 설명보다 이번 요구가 우선한다.

## 동작

- SHA-256 사용자별 집단 배정은 유지한다. 실험 이름과 해시를 변경하지 않았다.
- 두 군 모두 최근 완료 회고 최대 5건의 최종 관찰에서 세 차원을 도출한다. 진행 중, 안전 중단,
  답변이 전혀 없는 회고와 다른 사용자의 회고는 제외한다. 회고 종료 시 추가 AI 호출은 하지 않는다.
- TREATMENT는 세 차원을 매번 시작값으로 적용한다. CONTROL은 도출값을 기록하되 항상 초기 상태에서 시작한다.
- 이력이 없거나 특정 차원의 관찰 신호가 없으면 해당 차원은 기본값이다. 중립적인 완료 이력도 적용 이력에 포함한다.
- 첫 3일/4일 구분과 신호가 여러 대화에서 반복되어야 한다는 적용 대기 조건을 제거했다.

| 차원 | 서버 규칙 |
| --- | --- |
| 주도성 | 최근 완료 회고의 저항 신호가 하나라도 있으면 첫 탐색 질문부터 선택형으로, 이후 두 턴을 쉬어 질문한다. |
| 지지 대 도전 | 경청 요청과 다른 관점 요청 횟수를 비교해 경청 또는 재해석으로 시작한다. 동수면 최근 회고에서 더 많았던 쪽, 끝까지 동수/신호 없음이면 기본 탐색이다. |
| 감정 강도 | 감정 표현보다 회피 반응이 많으면 직접 기분 질문은 회고 전체에서 한 번까지, 기분 뒤 후속 탐색은 이야기당 한 번까지로 줄인다. 그 외에는 기존 기본 탐색이다. |

이 규칙은 초기 구현값이며 효과가 검증됐다는 뜻은 아니다. 세 차원은 서로 배제하지 않고 함께 반영한다.

## 현재 요청 우선

- `listen` / 확실한 질문 거부는 즉시 경청으로 전환한다. 첫 공통 질문 뒤의 거부도 적용한다.
- `explore` / `advice`는 저장된 경청·재해석을 풀고, 드문 질문과 감정 탐색 제한도 해제한다.
- `other_view`는 즉시 재해석으로 전환하고, 드문 질문과 감정 탐색 제한을 해제한다.
- 변경한 바로 그 턴의 문장 생성에도 새 설정을 전달한다. 이후 턴, 새 이야기, 저장/재개 시 과거 시작값을 다시 주입하지 않는다.
- 판단 확신 0.5 미만인 신호를 무시하는 기존 정책과 종료/안전 처리는 유지했다.

## 저장과 분석

- 대화 규칙 `v15`, 시작 성향 규칙 `always-v2`.
- V55는 기존 배정·관찰·도출값을 유지하면서 `derived_listen`, `derived_gentle_emotion`,
  `derived_history_sessions`, `disposition_version`, `past_disposition_applied`를 추가한다.
- 기존 행은 `conditional-v1`로 남기고 실제 반영 여부만 기존 TREATMENT/derived 값으로 복원한다.
- 새 집계는 `always-v2` 중 완료 회고 이력이 처음 생긴 세션부터 비교한다. 특정 성향 발생 여부로 거르지 않는다.
- 첫 답변 전에 떠난 세션도 포함하며, 기록된 시스템 오류는 이탈 분모에서 제외해 따로 센다.
- 선택형/재해석뿐 아니라 첫 턴 경청과 직접 기분 질문 수를 처치 확인으로 보고한다.
- V55 이전 결과와 새 실험의 결과를 합치지 않는다. 분석 시작/마감 시각은 배포/수집 계획에 맞춰 고정한다.

## 범위와 배포

- API 경로·메서드·요청/응답 DTO·인증 정책은 변경하지 않았다.
- 기존 스냅샷의 새 감정 설정은 기본값으로 읽는다. 새 설정은 회고 상태 JSON에 보존된다.
- 운영 DB에 마이그레이션을 적용하거나 서버를 배포하지 않았다. 변경은 별도 기능 브랜치에 커밋했으며 푸시·PR·머지는 하지 않았다.
- 배포 시 V55와 새 서버를 함께 반영해야 한다. 새 시작 성향은 배포 후 새로 시작하는 회고부터 적용된다.
- 유료 LLM 호출 없이 모델 대역으로 서버 규칙과 프롬프트를 검증했다. 실제 문구 품질은 별도 확인 대상이다.

## 검증과 리뷰

최종 결과: **PASS**.

- 관련 단위 테스트 163건 통과. Docker 시작 후 DB/집계 통합 테스트 21건 통과.
- 추가 V54→V55 마이그레이션 회귀 테스트와 DB/집계 통합 테스트 22건 통과(스킵 없음).
- 최종 `./gradlew clean check --offline --no-daemon`: BUILD SUCCESSFUL, 1분 55초.
  JUnit XML 기준 총 1,221건 중 1,189건 성공, 실패/오류 0건, 기존 조건부/실제 외부 모델 테스트 32건 스킵.
- 초기에 Docker가 없어 통합 테스트가 스킵됐지만, Docker를 시작해 위 검증을 실제 실행했다.
- `git diff --check` 통과. API/인증 계약 변경 없음. JPA 쿼리·마이그레이션·스냅샷 호환·영속 상태를 검증했다.
- 독립 읽기 전용 리뷰 서브에이전트가 총 3회 검토했다. 현재 요청의 변경이 생성 프롬프트에 늦게 반영되는 문제와
  첫 답변 전 이탈이 집계에서 빠지는 문제를 확인·수정했고 각각 회귀 테스트를 추가했다.
- 최종 리뷰에 남은 Critical/Major/Minor 지적 없음. 거절한 리뷰 지적 없음.
- 기능별 커밋을 완료했고 PR 검토 준비 상태다. 실제 모델의 문구 품질 및 운영 배포는 이 검증에 포함하지 않는다.

실행한 주요 명령:

```bash
./gradlew compileJava --offline --no-daemon
./gradlew test --offline --no-daemon --tests '*StartingDispositionTest' --tests '*DispositionStartTest' --tests '*StrategyPolicyTest' --tests '*StrategySignalsSnapshotTest' --tests '*StrategyTurnPromptTest' --tests '*RetrospectEngineStrategyTest' --tests '*TurnJudgmentPersistenceIntegrationTest'
./gradlew test --offline --no-daemon --tests '*DispositionStartMigrationIntegrationTest' --tests '*TurnJudgmentPersistenceIntegrationTest'
./gradlew clean check --offline --no-daemon
git status --short
git diff --check
```


## 파일 변경

생성:

- `docs/disposition-always-apply-delivery.md`
- `src/main/resources/db/migration/V55__record_three_dimension_disposition_start.sql`
- `src/test/java/com/momentory/retrospect/infrastructure/persistence/DispositionStartMigrationIntegrationTest.java`

수정:

- `eval/strategy-metrics/00_setup.sql`
- `eval/strategy-metrics/20_progress.sql`
- `eval/strategy-metrics/30_metrics.sql`
- `eval/strategy-metrics/README.md`
- `src/main/java/com/momentory/retrospect/application/DispositionStart.java`
- `src/main/java/com/momentory/retrospect/application/StrategyDiaryChat.java`
- `src/main/java/com/momentory/retrospect/domain/disposition/ConversationSignals.java`
- `src/main/java/com/momentory/retrospect/domain/disposition/StartingDisposition.java`
- `src/main/java/com/momentory/retrospect/domain/strategy/StrategyPolicy.java`
- `src/main/java/com/momentory/retrospect/domain/strategy/StrategySignals.java`
- `src/main/java/com/momentory/retrospect/infrastructure/ai/PromptFactory.java`
- `src/main/java/com/momentory/retrospect/infrastructure/persistence/DispositionObservationRecord.java`
- `src/main/java/com/momentory/retrospect/infrastructure/persistence/DispositionObservationRecordRepository.java`
- `src/main/resources/application.yml`
- `src/test/java/com/momentory/retrospect/application/DispositionStartTest.java`
- `src/test/java/com/momentory/retrospect/application/RetrospectEngineStrategyTest.java`
- `src/test/java/com/momentory/retrospect/domain/disposition/StartingDispositionTest.java`
- `src/test/java/com/momentory/retrospect/domain/strategy/StrategyPolicyTest.java`
- `src/test/java/com/momentory/retrospect/domain/strategy/StrategySignalsSnapshotTest.java`
- `src/test/java/com/momentory/retrospect/infrastructure/ai/StrategyTurnPromptTest.java`
- `src/test/java/com/momentory/retrospect/presentation/TurnJudgmentPersistenceIntegrationTest.java`

삭제: 없음.

## 브랜치와 커밋 구성

브랜치: `feat/three-dimension-disposition` (기준: `dev`, `7c172c6`).

1. `bd73022` — 회고 시작에 세 성향을 매번 반영한다. 시작값 적용, 현재 요청 우선, V55/JPA 기록과 관련 테스트를 함께 묶었다.
2. `0e71006` — 새 성향 실험의 분석 대상과 이탈 집계를 보정한다. 분석 SQL, 설명, SQL 실행 회귀 테스트를 함께 묶었다.
3. 이 문서는 동작 규칙·검증 결과를 정리하는 별도 문서 커밋으로 기록한다.

커밋 분리 중 기능 코드·테스트·SQL의 최종 내용은 변경하지 않았다. 전체 검증 결과는 위와 같다.
