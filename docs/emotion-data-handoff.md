# 감정 데이터 작업 인수인계 (2026-10-02)

다른 세션에서 이어 받을 때 **이 문서부터** 읽는다. 배경·결정의 정본은 Notion이고, 리포 정리본은
[model-comparison-plan.md](model-comparison-plan.md) (§7.4가 1차 학습·평가셋)이다.

## 0. 시작 전에

- 리포 `AGENTS.md`를 먼저 읽는다.
- 브랜치는 **`feat/emotion-data-v2`**이다. `feat/model-comparison-eval`에서 분기했고, 아직 푸시하지 않았으며 upstream도 없다.
  - 푸시할 때 upstream을 main으로 잡지 않는다.
  - 커밋에 Co-Authored-By 트레일러를 넣지 않는다.
  - 커밋은 사용자가 요청할 때만 한다.
- 커밋하지 않는 것:
  - `bin/` (IDE 빌드 결과)
  - `eval/data/` 전체 (AI Hub 라이선스 데이터와 실사용 데이터가 있다. gitignore 처리됨)
- 라벨링 화면은 localhost로 연다. file:// 로 열면 localStorage가 막힌다.

  ```bash
  cd /Users/geun/dev/projects/momentory/momentory-be/eval && python3 -m http.server 8765
  ```

  끌 때:

  ```bash
  lsof -ti:8765 | xargs kill
  ```

## 1. 목표 (한 줄)

공개 가중치 생성형 모델(Qwen3-8B 등, LoRA/QLoRA)을 SFT해서, 사건별로 **감정 · 강도(1/2/3/판단 어려움) · 근거 발화 · 판단 보류**를 모멘토리 감정 체계로 출력하게 만든다.

같은 평가셋에서 세 방식을 비교한다.
- ① 기존 LLM 프롬프팅
- ② SFT
- ③ KoBERT

**분담 방향은 확정됐다.**
- 감정 단어가 겉으로 드러나는 *명시* 사례는 기존 LLM이나 사전·규칙이 맡는다.
- 자체 모델은 *간접·복합* 사례를 맡는다.

## 2. 확정된 결정

| 항목 | 결정 |
|---|---|
| 감정 체계 | 대표 11 × 세부 5 = 55 (선택지 66) |
| 대표 감정 | 기쁨 · 평온 · 뿌듯함 · 애정 · 슬픔 · 불안 · 분노 · 실망 · 부끄러움 · 혼란 · **미안함** |
| 기쁨 세부 | 즐거움 → **고마움**으로 교체 |
| 미안함 세부 | 죄송함 · 죄책감 · 후회 · 자책감 · 면목없음 |
| 범위 밖 | 혐오 · 질투 (다시 제안하지 않는다) |
| 판단 보류 유형 | 감정 정보 부족(빈 목록이 정답) / 대표 감정 판단 어려움 / 분류 체계 밖 |
| AI Hub 018 라벨 | 사람 판단과 일치율이 25%라서 **텍스트만** 쓴다. 분포는 자연 분포가 **아니다** |
| 평가셋 | 층화해서 뽑고 **층별로 점수**를 낸다. 실제 비율을 알게 되면 가중치를 다시 매긴다 |
| 복합·경계 | 명시/간접/보류와 **다른 축**이다. 라벨링 뒤에 표시하고 최소 건수를 보장한다 |
| 실사용 대화 | 방침·약관을 고치고 옵트인 재동의를 받은 뒤에만 쓴다 (A안, [research-consent-rollout.md](research-consent-rollout.md)) |
| 기타창("직접 적기") | 어휘 사전 일치 → 임베딩 제안 → 체계 밖 순서. 명시/간접 분기에는 임베딩을 **쓰지 않는다** |

## 3. 지금 있는 것

### 커밋된 도구 (`eval/`, 사용법은 [eval/README.md](../eval/README.md))

| 파일 | 역할 |
|---|---|
| `labeler.html` | v2 사건 중심 라벨링 화면 (11종). 앱 대화처럼 긴 형식용 |
| `merge.py` | 2인 병합과 IAA (kappa, 가중 kappa, 명시 판단 kappa) |
| `dump.py` | 실사용 대화 덤프 (익명화·가명화) |
| `score.py` | **아직 v1 체계라서 v2 골드를 거부한다. 이식이 필요하다** |
| `aihub/mapping.json` · `convert.py` | AI Hub 소분류 60개 → 11종 매핑. 대응률 87.0% |
| `aihub/harvest.py` · `harvest.html` · `consolidate.py` | 애정·뿌듯 표적 수집 → `seeds.jsonl` (312건) |
| `aihub/sample.py` | 층화 추출 (인물 해시 분리, 근접 중복 거부, 상황 40% 상한) |
| `aihub/diversity.py` | 감정 묶음별 유사도·쏠림 점검, 학습–평가 유출 점검 |
| `lexicon/emotion_lexicon.json` · `check_lexicon.py` | 감정 어휘 사전 초안. 명시/간접 분류용 |

### 로컬 데이터 (`eval/data/`, 커밋 안 됨)

- `aihub-v2.jsonl`: AI Hub 전체를 11종으로 변환한 것
- `seeds.jsonl`: 표적 수집 결과
- **`sample-r1/`**: 1차 추출 결과

  | 파일 | 건수 | 구성 |
  |---|---:|---|
  | `pilot.jsonl` | 20 | 명시 6 · 간접 14. 2인이 기준을 맞추는 데 쓴다 |
  | `eval.jsonl` | 180 | 명시 60 (감정 단어 2개 이상 24건) · 간접 120 |
  | `safety.jsonl` | 50 | 11종 고르게. 애정·뿌듯 각 5건은 표적 수집에서 가져왔고 `prefill` 이 있다 |
  | `train.jsonl` | 250 | 간접만, 10종 × 25 (간접 애정은 AI Hub에 없다) |
  | `manifest.json` | — | seed, 인자, 점검 결과, `used_ids` (2차 추출 때 제외) |

- 레코드 필드: `id, role, stratum, lex, lexHits, multiWord, proxy, profile, split, aihub{major,minor,age,topic}, turns[{speaker,text}], assign(A/B), prefill?`
  - `proxy` 는 AI Hub 라벨을 11종으로 옮긴 것이다. 층화에만 쓰고 정답이 아니다.
- 점검은 모두 통과했다: 인물 겹침 0 · 중복 0 · 인물당 최대 2건 · 학습–평가 유출 0 · 평가 안 근접 중복 0.
- 남은 경고는 하나다: 평가셋 슬픔 묶음이 '친구' 주제로 8/18 쏠려 있다.

## 4. 다음 할 일 (순서대로)

1. **빠른 라벨링 화면 만들기** ← 바로 다음 작업
   - `sample-r1/*.jsonl` 을 불러온다. AI Hub 대화는 짧은 형식이라 사건 1개와 사용자 발화를 자동으로 채운다.
   - `lex`/`stratum` 으로 명시 표현 힌트를 보여준다.
   - 키보드만으로 입력한다:
     - 감정 11종
     - 강도 1/2/3/?
     - 근거 발화
     - 판단 보류 3유형
   - 라벨러 A/B를 고르고 `assign` 으로 거른다. 안전판은 `prefill` 을 미리 채운다.
   - `harvest.html` 에서 얻은 교훈을 그대로 따른다:
     - localhost로 서빙한다.
     - localStorage에는 판정만 저장하고, 항목은 지문(fingerprint)으로 맞춘다.
     - 내보내기 파일로 이어 하기를 지원한다.
     - 형식은 텍스트 전체를 `JSON.parse` 해 보고 판별한다.
     - `accept` 속성을 쓰지 않고 끌어다 놓기를 지원한다.
   - 내보내기는 `merge.py` 와 호환되는 v2 골드(`taxonomy:"v2"`)로 한다. `stratum` 도 남겨서, 나중에 감지기 기준 명시와 사람 기준 명시로 각각 잘라 평가할 수 있게 한다.
2. **학습용 보류 문장 25건 초안** — 내가 쓰고 사용자가 검토한다.
   - 평가용 보류 20건은 사용자가 직접 쓴다. 같은 말투가 학습과 평가에 섞이지 않게 하려는 것이다.
   - 보류 문장은 "감정이 생길 법한 사건인데 감정 표현이 없는 것"이다. 예: "친구가 약속을 취소했어".
3. **AI Hub 약관 결과 받아서 계획 문서에 기록** — 사용자는 확인했다고 했는데 내용은 아직 못 들었다. 상업 서비스 모델 학습에 쓸 수 있는지, 출처 표기나 재배포 금지 같은 조건이 있는지 묻는다.
4. **어휘 사전 검토 (사용자)**
   - 평가어(힘들다·한심·눈물 등)를 기본값 간접으로 둘지
   - 모호 표현 10개의 후보 감정
   - 빠진 구어 표현
5. **라벨링 뒤 작업**
   - `merge.py` 로 IAA를 낸다.
   - 실제 정답으로 `diversity.py` 를 다시 돌린다.
   - 2차 추출: 부족한 감정과 복합 사례를 겨냥하고, `used_ids` 는 제외한다.
   - **`score.py` v2 이식**: 11종으로 바꾸고, 층별 점수(명시/간접/보류/복합/경계, 감지기 기준과 사람 기준 명시)를 낸다.
   - 학습 곡선을 150 → 300 → 500으로 그린다.
6. **병행 트랙**
   - Notion 「AI 모델을 써야하는 이유」의 비용 단락이 분담 방향과 반대로 적혀 있다. 고쳐야 한다.
   - 재동의:
     - 방침·약관 문구와 법률 검토
     - BE `research_consents` 와 API (API 계약 변경이라 승인 필요)
     - FE 옵트인
   - 운영 `Emotion` enum을 11종으로 옮긴다 (API 계약 변경이라 승인 필요).
   - 그 뒤에 기타창 BE를 고친다. `RetrospectEngine.handleEmotionConfirm` 이 `Emotion.fromLabel` 정확 일치만 쓰고 있다.

## 5. Notion 페이지

| 페이지 | 링크 |
|---|---|
| AI 채팅 흐름 및 평가 설계 (정본, 11/55/66) | https://app.notion.com/p/AI-3e8b5a896e42807a8946dd0877fb299f |
| 감정 라벨링 기준 초안 v0.2 | https://app.notion.com/p/v0-2-3e8b5a896e42804aabcac826749cd64d |
| AI 모델을 써야하는 이유 | https://app.notion.com/p/AI-3ebb5a896e4280f5aebef58f50cc252b |
| 데이터 수집 (맨 위에 "데이터 작업 정리") | https://app.notion.com/p/3e8b5a896e4280d68c73f2c5ea265802 |

- Notion MCP는 인증되어 있지 않다. 사용자가 내장 브라우저에서 로그인한 상태로 편집한다.
- 팀 문서이므로 사용자가 요청할 때만 고친다.
