# 모델 비교 채점기

계획: [`../docs/model-comparison-plan.md`](../docs/model-comparison-plan.md)

## 무엇을 위한 것인가

**버전이 다른 모델을 같은 잣대로 비교하기 위한 것이다.** 채점기는 예측이 어디서 왔는지 모른다 —
규칙 베이스라인(V0)이든 LLM(V1)이든 KLUE-RoBERTa(V2)든, 같은 JSON 모양이기만 하면 같은 기준으로
잰다. 문서가 "모든 버전은 반드시 동일한 테스트 세트에서 평가해야 한다"고 한 것을 실행 가능하게
만드는 자리다.

그래서 **프로덕션 소스에 넣지 않는다**(계획 §11). 서비스가 아니라 실험 도구다.

## 실행

```bash
python3 score.py --gold gold.json --pred v0=pred-v0.json --pred v1=pred-v1.json
```

의존성 없음(표준 라이브러리만). 샘플로 바로 확인할 수 있다:

```bash
python3 score.py --gold sample/gold.sample.json \
  --pred v0=sample/pred-v0.sample.json --pred v1=sample/pred-v1.sample.json --per-emotion v1
```

옵션:

| 옵션 | 뜻 |
|---|---|
| `--pred 이름=경로` | 버전 예측. 여러 번 줄 수 있다 |
| `--set natural\|challenge` | 골드의 `set` 으로 거른다 |
| `--per-emotion 버전` | 그 버전의 감정별 P/R/F1 을 함께 낸다 |

**Micro F1·Jaccard 는 `--set natural` 로 봐야 한다** (계획 §5.2). challenge set 은 희소 감정을
의도적으로 몰아넣은 세트라 실제 분포가 아니다. Macro F1·감정별 F1 은 두 세트를 합쳐서 본다.

## 파일럿 도구

```
대화 수집(앱)  ──psql──▶  dump.py  ──┬──▶ 대화(프로브 형식) ──▶ 프로브 러너 ──▶ 예측
                                     └──▶ 예측(운영 설정)
                                                                    labeler.html ──▶ 골드
                                                                                      │
                                                            score.py ◀────────────────┘
```

### `dump.py` — DB 의 state_json 을 꺼낸다

DB 에 직접 붙지 않는다. 내보내기는 psql 이 하고 변환만 맡는다.

```bash
psql "$DB_URL" -At -c \
  "SELECT state_json FROM retrospects WHERE status='COMPLETE' ORDER BY id" > sessions.jsonl

python3 dump.py --in sessions.jsonl \
  --conversations pilot-conversations.txt \
  --predictions pilot-pred-prod.json
```

- **대화**는 프롬프트 변형을 갈아가며 예측을 다시 뽑을 입력이다. 보통 이쪽만 쓴다.
- **예측**은 앱이 운영 설정(temperature 0.7)으로 이미 만든 결과다. "운영이 실제로 뭘 냈나"를
  볼 때만 쓴다. 감정이 0개인 세션을 세어 **조용한 호출 실패**를 알려 준다.
- 선택지 줄은 걷어낸다. `PromptFactory` 도 프롬프트를 만들 때 같은 줄을 빼므로 재실행 프롬프트는
  동일하다.

### 프로브 러너 — 같은 대화에 변형을 갈아 끼운다

```bash
PROBE_IN=pilot-conversations.txt PROBE_OUT=/tmp/pred-comb.json \
PROBE_VARIANT=FEW_SHOT_COMBINED \
  ./gradlew cleanTest test --tests "*EmotionExtractionProbeRunner"
```

`cleanTest` 를 꼭 붙인다 — Gradle 은 환경변수를 입력으로 추적하지 않아 변형만 바꾸면 건너뛴다.
temperature 0 과 RPM 스로틀(4.5초)이 내장돼 있다.

### `labeler.html` — 골드를 다는 화면

브라우저에서 그냥 연다(파일 하나, 서버 불필요).

- 대화를 `[U1]` `[U2]` 로 번호 붙여 보여주고, **발화를 눌러** 사건 배정·감정 근거를 찍는다
- 감정 10종 · 강도 1~4 · 시점 4종을 클릭으로. **강도 앵커가 화면에 상시 표시**된다
- **감정 없음**과 **판단 불가**를 구분한다 — 전자는 정답(빈 목록), 후자는 채점 제외
- 불러올 때 **순서를 랜덤화**한다(감정별로 몰아서 라벨링하면 기준이 미끄러진다 — 계획 §6.3)
- 모델 출력을 보여주지 않는다(blind)
- 작업은 localStorage 에 자동저장되지만 **수시로 「JSON 내보내기」** 를 눌러 파일로 남길 것

## 데이터 계약

골드와 예측은 같은 모양이다(골드에만 `set`·`experiencer` 가 붙는다). 필드는 `ExtractedEvent`·
`ExtractedEmotion` 과 1:1이다.

```json
[
  {
    "sessionId": "s-001",
    "set": "natural",
    "events": [
      {"id": 1, "label": "팀 발표", "summary": "발표 중 말이 막혔지만 끝까지 마침",
       "evidence": [1, 2, 3]}
    ],
    "emotions": [
      {"eventId": 1, "normalized": "anxious", "intensity": 3, "phase": "before",
       "evidenceIds": [2]},
      {"eventId": 1, "normalized": "angry", "experiencer": "other", "intensity": 4,
       "phase": "during", "evidenceIds": [1]}
    ]
  }
]
```

- `evidence` — 그 사건에 속하는 **사용자 발화 번호 전체**. 사건 정렬의 기준이다.
- `evidenceIds` — 그 감정의 근거 발화 번호. `evidence` 의 부분집합.
- `experiencer` — **골드에만.** `"other"` 면 타인 감정이라 자기 감정 채점에서 빠지고,
  타인→자기 오탐률의 분모가 된다. 생략하면 `"self"`.
- 감정 키는 `Emotion.java` 의 10종, `phase` 는 `before|during|after|now`.

## 지표

### 세션 단위 — 사건 정렬과 무관

**대표 지표가 정렬 규칙의 자의성에 오염되지 않도록** 감정·강도·시점은 사건을 무시하고
세션 단위로 잰다(계획 §7).

| 지표 | 뜻 |
|---|---|
| 감정 Macro F1 | **대표 지표.** 10종을 동등하게 — 희소 감정을 못 잡는 걸 잡아낸다 |
| 감정 Micro F1 | 전체 예측 성능. 자주 나오는 감정이 지배한다 |
| Jaccard | 정답 감정 집합과 예측 집합의 겹침 |
| 라벨 개수 오차 · Hamming Loss | 과다/과소 예측 |
| 강도 MAE · QWK | 평균 몇 칸 틀렸나 / 순서형 판단이 얼마나 일치하나 |
| 시점 Macro F1 | `before/during/after/now` 4종 |
| 타인→자기 오탐률 | 타인 감정을 사용자 감정으로 잡은 비율 |

### 사건 — 정렬 필요 (계획 §7.1)

근거 발화 집합의 **IoU** 로 짝짓는다. IoU 합이 최대인 이분 매칭을 고르고, 임계값 미만인 짝은
파기해서 짝 없는 예측은 FP, 짝 없는 정답은 FN 으로 센다.

**임계값 0.3 / 0.5 / 0.7 세 값으로 모두 낸다.** 볼 것은 절대값이 아니라 **순위가 유지되는가**다.
임계값에 따라 버전 순위가 뒤집히면 사건 F1 만으로 버전을 고를 수 없다는 뜻이고, 그건 실패가
아니라 보고할 발견이다.

| 지표 | 뜻 |
|---|---|
| 사건 개수 정확도 | 정렬 없이 개수만 비교 |
| Event Detection F1 | 매칭/FP/FN 에서 |
| Turn Assignment Macro F1 | 각 발화가 올바른 사건에 배정됐나 |
| Event Attribution | 감정이 올바른 사건에 붙었나(매칭된 사건에 한해) |

## 채점 규칙에서 정한 것

읽는 사람이 숫자를 오해하지 않도록 명시한다. 셋 다 파일럿에서 재검토 대상이다.

1. **같은 감정이 한 세션에 여러 번 나오면 가장 강한 것으로 접는다.** 강도·시점을 한 쌍씩
   비교하려면 (세션, 감정) 하나에 값이 하나여야 한다. 대표로 삼는 것은 가장 강한 표현이라고 본다.
2. **골드에도 예측에도 없는 감정은 Macro F1 평균에서 뺀다.** 그 라벨의 F1 은 0 이 아니라
   '정의되지 않음'이다. 넣으면 테스트 세트가 작을 때 값이 실제보다 훨씬 낮게 보인다.
   예측만 있고 골드에 없는 라벨은 남긴다(F1=0) — 없는 감정을 지어낸 것에 벌점이 가야 한다.
   표에 `채점한 감정 라벨 수` 를 함께 내니 그 값과 같이 읽을 것.
3. **분모를 함께 낸다.** `강도 비교 쌍 수`·`귀속 판정 수` 가 작으면 그 지표는 못 믿는다.
   (샘플에서 V0 의 Event Attribution 이 1.000 인데 판정 수가 1이다 — 이런 걸 보라고 낸다.)

## 자동 채점하지 않는 것

- **Event Summary Score** — BERTScore(별도 모델)와 사람 평가가 필요하다. 문서가 ROUGE 대신
  BERTScore 를 쓰라고 한 이유는 표현이 달라도 의미가 같을 수 있어서다.
- **신뢰도(ECE·Brier·Coverage)** — 확률 출력이 필요한데 V1 은 내지 않는다. V2 부터 대상이다.
- **5줄 일기 평가** — 감정 모델과 분리해서 평가한다(계획 §7).

## 평가할 때 temperature

**0 으로 내리고 돌린다.** 운영은 0.7 을 유지하고(일기 문장이 딱딱해지지 않게), 평가에서만 내린다 —
0.7 이면 같은 입력에 결과가 흔들려 버전 간 차이인지 노이즈인지 구분할 수 없다.

```bash
SPRING_PROFILES_ACTIVE=local,eval ./gradlew bootRun
```

`application-eval.yml` 오버레이가 `temperature: 0` 과 프롬프트 변형 스윕용
`EMOTION_PROMPT_VARIANT` 를 걸어 준다.
