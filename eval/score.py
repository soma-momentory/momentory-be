#!/usr/bin/env python3
"""
모델 비교 채점기 (모델 비교 계획 §9).

골드 라벨과 한 개 이상 버전의 예측을 받아 동일한 기준으로 채점하고 성능표를 낸다.
**모델에 무관하다** — 예측이 규칙(V0)에서 왔든 LLM(V1)에서 왔든 파인튜닝 모델(V2/V3)에서
왔든, 같은 JSON 모양이기만 하면 같은 잣대로 잰다. 그게 이 파일이 존재하는 이유다.

**정본(v2) 전용이다.** 대표 감정 12종 · 강도 1·2·3 + null(판단 어려움). 시점(phase)은 채점하지
않는다(계획 §4.1 — 필드가 있어도 무시한다).

사용법:
    python3 score.py --gold gold.json --pred v0=pred-v0.json --pred v1=pred-v1.json

입력 (eval/README.md 「데이터 계약」):
    골드 — labeler.html · quick-labeler.html 이 내는 v2 골드.
    예측 — 골드와 같은 모양에서 sessionId · events(id, evidence) · emotions(eventId, normalized,
           intensity) 만 있으면 된다. 구 채점기와 달라진 점:
           · **모든 행에 taxonomy: "v2" 가 있어야 한다.** 없으면 구 택소노미(v1)로 보고 거부한다
             (예전에도 없으면 v1 이었다). 감정 키가 12종 밖이거나 강도가 1·2·3·null 밖이어도 거부한다
           · normalized 를 비운 감정(체계 밖 — 계획 §4.2)과 experiencer: "other" 는 채점에서 뺀다

감정 목록과 가중 카파는 merge.py 에서 가져온다 — IAA(상한)와 모델 점수가 같은 정의를 쓰게.
의존성 없음(표준 라이브러리만). numpy·sklearn 없이 돌게 두어 어디서든 바로 실행된다.
"""

import argparse
import json
import sys
import unicodedata
from collections import Counter, defaultdict
from itertools import permutations

from merge import EMOTIONS, KO, quadratic_weighted_kappa

TAXONOMY = "v2"
INTENSITIES = (1, 2, 3)  # null 은 등급이 아니라 보류 — 따로 센다(계획 §9.3)
# 판단 불가 3종 중 채점에서 빼는 둘(계획 §8.3). 감정 정보 부족(no_emotion_info)은 빈 목록이 정답이라 채점한다.
BLOCKING = ("emotion_unclear", "out_of_scope")

# 사건 정렬 IoU 임계값 (계획 §9.1) — 기본 0.5, 0.3·0.7 은 민감도.
IOU_DEFAULT = 0.5
IOU_THRESHOLDS = [0.3, 0.5, 0.7]


def check_contract(rows, who):
    """
    택소노미·감정 키·강도 척도가 채점기와 같은지 본다. 하나라도 어긋나면 채점하지 않는다.

    가장 위험한 것은 라벨 키가 안 겹쳐 **조용히 전부 0점**이 나오는 것이다. 구 택소노미(v1)는
    anxious·angry·proud·calm 네 키가 v2 와 같아서 키만 봐서는 다 못 거른다 — 그래서 taxonomy
    필드를 먼저 요구하고, 키와 강도는 그다음 방어선으로 본다.
    """
    problems = []
    tax = Counter(r.get("taxonomy") for r in rows)
    bad_tax = {t: n for t, n in tax.items() if t != TAXONOMY}
    if bad_tax:
        problems.append("taxonomy 가 v2 가 아닌 행 — " + ", ".join(
            "{} {}건".format(t or "없음", n) for t, n in bad_tax.items()))
    emos = [m for r in rows for m in r.get("emotions") or [] if m.get("normalized")]
    bad_keys = Counter(m["normalized"] for m in emos if m["normalized"] not in EMOTIONS)
    if bad_keys:
        problems.append("12종 밖 감정 키 — " + ", ".join(
            "{} {}개".format(k, n) for k, n in bad_keys.most_common()))
    bad_int = Counter(m.get("intensity") for m in emos
                      if m.get("intensity") is not None and m.get("intensity") not in INTENSITIES)
    if bad_int:
        problems.append("강도가 1·2·3·null 밖 — " + ", ".join(
            "{} {}개".format(k, n) for k, n in bad_int.most_common()))
    if problems:
        sys.exit("{}: 정본(v2) 계약과 다릅니다. 이대로 채점하면 값이 조용히 틀립니다.\n  · {}\n"
                 "  예측이면 v2 키로 바꾸고 모든 행에 \"taxonomy\": \"v2\" 를 붙이세요(eval/README.md 「데이터 계약」)."
                 .format(who, "\n  · ".join(problems)))


# ── 기본 통계 ────────────────────────────────────────────────────────────

def prf(tp, fp, fn):
    p = tp / (tp + fp) if tp + fp else 0.0
    r = tp / (tp + fn) if tp + fn else 0.0
    f = 2 * p * r / (p + r) if p + r else 0.0
    return p, r, f


def macro_f1(counts, labels):
    """
    라벨별 F1 의 단순 평균 — 희소 라벨이 자주 나오는 라벨에 묻히지 않는다.

    <b>골드에도 예측에도 한 번도 안 나온 라벨은 평균에서 뺀다.</b> 그런 라벨의 F1 은 0 이 아니라
    '정의되지 않음'이다. 넣어버리면 테스트 세트가 작을 때 Macro F1 이 실제보다 훨씬 낮게 보인다
    (12종 중 6종이 미등장이면 다 맞혀도 0.5 가 최대). 예측만 있고 골드에 없는 라벨은 남겨서
    (F1=0) 없는 감정을 지어낸 것에 벌점이 가게 한다.

    :return: (macro F1, 평균에 들어간 라벨 수) — 라벨 수를 함께 봐야 값을 해석할 수 있다.
    """
    used = [l for l in labels if counts[l][0] + counts[l][1] + counts[l][2] > 0]
    if not used:
        return None, 0
    return sum(prf(*counts[l])[2] for l in used) / len(used), len(used)


def micro_f1(counts, labels):
    """골드에도 예측에도 감정이 하나도 없으면(보류 층에서 다 맞힌 경우) 정의되지 않는다."""
    tp = sum(counts[l][0] for l in labels)
    fp = sum(counts[l][1] for l in labels)
    fn = sum(counts[l][2] for l in labels)
    return prf(tp, fp, fn)[2] if tp + fp + fn else None


def mean(xs):
    return sum(xs) / len(xs) if xs else None


def ratio(a, b):
    return a / b if b else None


# ── 사건 정렬 (계획 §9.1) ────────────────────────────────────────────────

def iou(a, b):
    """
    발화 번호 집합의 교집합 ÷ 합집합. 넉넉히 담아도 적게 담아도 점수가 깎인다.

    근거가 빈 사건은 어디에도 짝지어지지 않는다(0). 근거 없이 뽑은 사건은 FP 로 잡는 것이
    의도된 페널티다(계획 §4.1). merge.py 는 사람끼리 비교라 둘 다 비면 1 로 보는데, 여기서
    그렇게 하면 근거 없는 예측 사건이 근거 없는 골드 사건과 만점으로 짝지어진다.
    """
    sa, sb = set(a or []), set(b or [])
    union = sa | sb
    return len(sa & sb) / len(union) if union else 0.0


def align_events(gold_events, pred_events, threshold):
    """
    임계값 이상인 쌍만 후보로 두고, IoU 합이 최대가 되는 이분 매칭을 찾는다.

    합이 최대인 매칭을 먼저 고르고 임계값으로 자르면 안 된다 — 0.9 + 0.2 매칭을 고른 뒤 0.2 를
    버려서, 0.55 + 0.52 로 둘 다 살릴 수 있는 매칭을 놓친다. merge.py 도 임계값 이상만 후보로
    둔다. 사건이 최대 2개라 조합을 다 본다.

    :return: (matched, unmatched_gold, unmatched_pred)
             matched 는 [(gold_event, pred_event, iou)]
    """
    if not gold_events or not pred_events:
        return [], list(gold_events), list(pred_events)

    swap = len(gold_events) > len(pred_events)
    short, long_ = (pred_events, gold_events) if swap else (gold_events, pred_events)

    best, best_score = [], 0.0
    for combo in permutations(range(len(long_)), len(short)):
        pairs = []
        for si, li in enumerate(combo):
            g, p = (long_[li], short[si]) if swap else (short[si], long_[li])
            v = iou(g.get("evidence"), p.get("evidence"))
            if v >= threshold:
                pairs.append((g, p, v))
        score = sum(v for _, _, v in pairs)
        if score > best_score:
            best, best_score = pairs, score

    matched_gold = {id(g) for g, _, _ in best}
    matched_pred = {id(p) for _, p, _ in best}
    return (best,
            [g for g in gold_events if id(g) not in matched_gold],
            [p for p in pred_events if id(p) not in matched_pred])


# ── 감정·강도 (세션 단위 — 정렬과 무관, 계획 §9.2·9.3) ──────────────────

def blocked(row):
    """
    대표 감정 판단 어려움·분류 체계 밖 사건이 있는 세션 — 감정·강도 채점에서 <b>통째로</b> 뺀다.

    사건 하나만 빼려면 예측 감정이 그 사건 것인지 가려야 하고, 그러려면 사건 정렬이 필요하다.
    세션 단위 지표를 정렬과 무관하게 두려고 세션째 뺀다. AI Hub 표본은 사건이 1개라 같은 결과다.
    """
    return any(e.get("undecidable") in BLOCKING for e in row.get("events") or [])


def self_emotions(row):
    """자기 감정만. 타인 감정(experiencer='other')과 normalized 를 비운 감정(체계 밖)은 뺀다."""
    return [m for m in row.get("emotions") or []
            if m.get("experiencer") != "other" and m.get("normalized")]


def other_emotions(row):
    return [m for m in row.get("emotions") or []
            if m.get("experiencer") == "other" and m.get("normalized")]


def strongest_by_label(emotions):
    """
    같은 감정이 한 세션에 여러 번 나오면 <b>가장 강한 것</b>으로 접는다(null 보다 등급이 우선).

    강도를 한 쌍씩 비교하려면 (세션, 감정) 하나에 값이 하나여야 한다. merge.py 의 IAA 도 같은
    규칙으로 접으므로 상한과 바로 비교된다.
    """
    best = {}
    for m in emotions:
        label, v = m["normalized"], m.get("intensity")
        cur = best.get(label)
        if cur is None or (v is not None and (cur.get("intensity") is None or v > cur["intensity"])):
            best[label] = m
    return best


def score_emotions(pairs):
    """pairs: [(gold_session, pred_session)] — 채점 제외 세션(blocked)은 이미 뺀 것."""
    counts = {l: [0, 0, 0] for l in EMOTIONS}          # label -> [tp, fp, fn]
    jaccards, exact, count_errors = [], [], []
    empty_gold, empty_gold_hit = 0, 0                  # 정답이 빈 목록(감정 정보 부족)
    filled_gold, empty_pred_miss = 0, 0                # 정답은 있는데 예측이 빈 목록
    int_pairs = []                                     # 둘 다 등급을 낸 (골드, 예측)
    targets, matched, gold_null, pred_null, both_null = 0, 0, 0, 0, 0
    false_self, other_total = 0, 0

    for gold, pred in pairs:
        g_by = strongest_by_label(self_emotions(gold))
        p_by = strongest_by_label(self_emotions(pred))
        g_set, p_set = set(g_by), set(p_by)

        for label in EMOTIONS:
            in_g, in_p = label in g_set, label in p_set
            if in_g and in_p:
                counts[label][0] += 1
            elif in_p:
                counts[label][1] += 1
            elif in_g:
                counts[label][2] += 1

        union = g_set | p_set
        jaccards.append(len(g_set & p_set) / len(union) if union else 1.0)
        exact.append(g_set == p_set)
        count_errors.append(abs(len(p_set) - len(g_set)))
        if g_set:
            filled_gold += 1
            empty_pred_miss += not p_set
        else:
            empty_gold += 1
            empty_gold_hit += not p_set

        # 강도 — 골드 감정마다. 응답 비율의 분모는 '골드가 등급을 매긴 감정 전부'다: 감정을 놓친 것과
        # 강도를 보류한 것 모두 '응답 안 함'으로 잡아야 쉬운 사례만 고른 정확도가 되지 않는다(계획 §9.3).
        for label in g_set:
            gi = g_by[label].get("intensity")
            targets += gi is not None
            if label not in p_set:
                continue
            pi = p_by[label].get("intensity")
            matched += 1
            gold_null += gi is None
            if pi is None:
                pred_null += 1
                both_null += gi is None
            elif gi is not None:
                int_pairs.append((int(gi), int(pi)))

        # 타인 감정을 사용자 감정으로 잡았는가 — 골드 자기 감정에 없는데 예측에 있으면 오탐.
        for m in other_emotions(gold):
            other_total += 1
            if m["normalized"] not in g_set and m["normalized"] in p_set:
                false_self += 1

    n = len(pairs)
    macro, labels_scored = macro_f1(counts, EMOTIONS)
    return {
        "n": n,
        "emotion_macro_f1": macro,
        "emotion_labels_scored": labels_scored,
        "emotion_micro_f1": micro_f1(counts, EMOTIONS),
        "jaccard": mean(jaccards),
        "set_exact": mean(exact),
        "label_count_error": mean(count_errors),
        "hamming_loss": sum(c[1] + c[2] for c in counts.values()) / (n * len(EMOTIONS)) if n else None,
        "empty_hit": ratio(empty_gold_hit, empty_gold),
        "empty_gold": empty_gold,
        "empty_miss": ratio(empty_pred_miss, filled_gold),
        "false_self_rate": ratio(false_self, other_total),
        "other_total": other_total,
        "intensity_exact": mean([g == p for g, p in int_pairs]),
        "intensity_mae": mean([abs(g - p) for g, p in int_pairs]),
        "intensity_two_step": mean([abs(g - p) == 2 for g, p in int_pairs]),
        "intensity_qwk": quadratic_weighted_kappa(int_pairs),
        "intensity_pairs": len(int_pairs),
        "intensity_targets": targets,
        "intensity_response": ratio(len(int_pairs), targets),
        "intensity_abstain": ratio(pred_null, matched),
        "intensity_abstain_ok": ratio(both_null, pred_null),
        "intensity_gold_null": gold_null,
        "per_emotion": {l: prf(*counts[l]) for l in EMOTIONS},
        "per_emotion_support": {l: counts[l][0] + counts[l][2] for l in EMOTIONS},
        "per_emotion_predicted": {l: counts[l][0] + counts[l][1] for l in EMOTIONS},
    }


# ── 사건 (정렬 필요, 계획 §9.1·9.2) ─────────────────────────────────────

def score_events(pairs, threshold):
    det = [0, 0, 0]                                   # tp, fp, fn
    count_hits, summary_missing = 0, 0
    turn_counts = defaultdict(lambda: [0, 0, 0])      # class -> [tp, fp, fn]
    attr_correct, attr_total = 0, 0
    set_hits, set_total = 0, 0

    for gold, pred in pairs:
        g_events = gold.get("events") or []
        p_events = pred.get("events") or []
        if len(g_events) == len(p_events):
            count_hits += 1

        matched, un_g, un_p = align_events(g_events, p_events, threshold)
        det[0] += len(matched)
        det[1] += len(un_p)
        det[2] += len(un_g)
        summary_missing += sum(1 for _, p, _ in matched if not (p.get("summary") or "").strip())

        # 발화 배정 — 예측 사건 id 를 매칭된 골드 사건 id 로 옮겨 비교한다.
        pred_to_gold = {p.get("id"): g.get("id") for g, p, _ in matched}
        g_assign, p_assign = {}, {}
        for e in g_events:
            for u in e.get("evidence") or []:
                g_assign[u] = e.get("id")
        for e in p_events:
            mapped = pred_to_gold.get(e.get("id"), "spurious")
            for u in e.get("evidence") or []:
                p_assign[u] = mapped
        for u in set(g_assign) | set(p_assign):
            gl = g_assign.get(u, "none")
            pl = p_assign.get(u, "none")
            if gl == pl:
                turn_counts[gl][0] += 1
            else:
                turn_counts[pl][1] += 1
                turn_counts[gl][2] += 1

        # 사건 귀속 — 감정이 올바른 사건에 붙었는가(매칭된 사건에 한해).
        p_self = self_emotions(pred)
        p_by_label = defaultdict(list)
        for m in p_self:
            p_by_label[m["normalized"]].append(m)
        g_self = self_emotions(gold)
        for m in g_self:
            if m.get("eventId") is None:
                continue
            candidates = p_by_label.get(m["normalized"])
            if not candidates:
                continue                              # 감정 자체를 못 맞힘 — 감정 F1 이 이미 벌한다
            attr_total += 1
            if any(pred_to_gold.get(c.get("eventId")) == m["eventId"] for c in candidates):
                attr_correct += 1

        # 사건별 감정 집합 일치 — 짝지은 사건의 감정 목록을 통째로 맞혔는가(계획 §9.2).
        # 감정 정보 부족 사건은 빈 목록이 정답이고, 판단 어려움·체계 밖 사건은 뺀다.
        for g, p, _ in matched:
            if g.get("undecidable") in BLOCKING:
                continue
            gs = {m["normalized"] for m in g_self if m.get("eventId") == g.get("id")}
            ps = {m["normalized"] for m in p_self if m.get("eventId") == p.get("id")}
            set_total += 1
            set_hits += gs == ps

    classes = sorted(turn_counts, key=str)
    return {
        "event_count_accuracy": ratio(count_hits, len(pairs)),
        "event_detection_f1": prf(*det)[2],
        "event_detection_pr": prf(*det)[:2],
        "turn_assignment_macro_f1": macro_f1(turn_counts, classes)[0],
        "event_attribution_accuracy": ratio(attr_correct, attr_total),
        "event_attribution_n": attr_total,
        "event_set_match": ratio(set_hits, set_total),
        "event_set_n": set_total,
        "summary_missing": summary_missing,
    }


# ── 층 (계획 §9.2 명시적·간접 표현별 + 보류·복합·경계) ──────────────────

def human_explicit(row):
    """사람의 명시 판단. 사건이 여럿이면 하나라도 yes 면 yes, 아니면 unsure, 아니면 no.
    AI Hub 표본은 사건이 1개라 events[0].explicit 그대로다."""
    vals = {e.get("explicit") for e in row.get("events") or []}
    return next((v for v in ("yes", "unsure", "no") if v in vals), None)


def traits(row):
    return {t for e in row.get("events") or [] for t in e.get("traits") or []}


# 골드로만 가른다 — 예측이 무엇을 냈든 같은 세션이 같은 층에 들어간다.
LAYERS = [
    ("전체",                 lambda g: True),
    ("명시 (감지기)",         lambda g: g.get("stratum") == "명시"),
    ("간접 (감지기)",         lambda g: g.get("stratum") == "간접"),
    ("명시 (사람)",           lambda g: human_explicit(g) == "yes"),
    ("간접 (사람)",           lambda g: human_explicit(g) == "no"),
    ("명시 판단 어려움 (사람)", lambda g: human_explicit(g) == "unsure"),
    ("보류 (감정 정보 부족)",  lambda g: not self_emotions(g)),
    ("복합 (mixed)",          lambda g: "mixed" in traits(g)),
    ("경계 (boundary)",       lambda g: "boundary" in traits(g)),
]


# ── 입출력 ───────────────────────────────────────────────────────────────

def load(path, who):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    check_contract(data, who)
    return {s["sessionId"]: s for s in data}


def build_pairs(gold, pred, filters):
    pairs, missing = [], []
    for sid, g in gold.items():
        if any(value and g.get(key) != value for key, value in filters.items()):
            continue
        if sid not in pred:
            missing.append(sid)
            continue
        pairs.append((g, pred[sid]))
    return pairs, missing


def fmt(v, digits=3):
    if v is None:
        return "—"
    if isinstance(v, int):
        return str(v)
    return f"{v:.{digits}f}"


def width(s):
    """한글은 터미널에서 두 칸을 먹는다 — ljust 로는 열이 안 맞는다."""
    return sum(2 if unicodedata.east_asian_width(c) in "WF" else 1 for c in s)


def print_table(title, header, rows, left=1):
    """앞의 left 개 열은 왼쪽, 나머지는 오른쪽 정렬."""
    ws = [max(width(r[i]) for r in [header] + rows) for i in range(len(header))]
    total = sum(ws) + 2 * (len(ws) - 1)
    rule = max(total, width(title))

    def line(cells):
        return "  ".join(c + " " * (ws[i] - width(c)) if i < left else " " * (ws[i] - width(c)) + c
                         for i, c in enumerate(cells)).rstrip()

    print("\n" + "=" * rule)
    print(title)
    print("=" * rule)
    print(line(header))
    print("-" * total)
    for r in rows:
        print(line(r))


def report(results, per_emotion_for=None):
    versions = list(results)

    def by_version(rows, group):
        return [[label] + [fmt(results[v][group][key]) for v in versions] for label, key in rows]

    print_table("감정 — 세션 단위, 사건 정렬과 무관 (계획 §9.2)", ["지표"] + versions, by_version([
        ("감정 Macro F1 (12종)",          "emotion_macro_f1"),
        ("감정 Micro F1",                 "emotion_micro_f1"),
        ("Jaccard",                       "jaccard"),
        ("감정 집합 완전 일치율",          "set_exact"),
        ("라벨 개수 오차 ↓",               "label_count_error"),
        ("Hamming Loss ↓",                "hamming_loss"),
        ("빈 목록 정답 맞힘",              "empty_hit"),
        ("과잉 보류 ↓ (정답 있는데 빈 목록)", "empty_miss"),
        ("타인→자기 오탐률 ↓",             "false_self_rate"),
        ("· 채점한 세션",                  "n"),
        ("· 채점한 감정 라벨 수",           "emotion_labels_scored"),
        ("· 빈 목록 정답 세션",             "empty_gold"),
        ("· 타인 감정 수",                 "other_total"),
    ], "emotion"))

    print_table("강도 — 1·2·3, null = 판단 어려움 (계획 §9.3). 정확도는 응답 비율과 함께 읽는다",
                ["지표"] + versions, by_version([
        ("정확 일치율",                    "intensity_exact"),
        ("MAE ↓",                         "intensity_mae"),
        ("2단계 오차율 ↓ (1↔3)",           "intensity_two_step"),
        ("가중 카파 (QWK, 1~3)",           "intensity_qwk"),
        ("응답 비율",                      "intensity_response"),
        ("판단 보류율",                    "intensity_abstain"),
        ("보류 적절성 (골드도 보류)",       "intensity_abstain_ok"),
        ("· 비교 쌍 (둘 다 등급)",          "intensity_pairs"),
        ("· 평가 대상 (골드 등급)",         "intensity_targets"),
        ("· 골드 보류 (맞힌 감정 중)",      "intensity_gold_null"),
    ], "emotion"))

    event_rows = [
        ("사건 개수 정확도",          "event_count_accuracy"),
        ("Event Detection F1",        "event_detection_f1"),
        ("Turn Assignment F1",        "turn_assignment_macro_f1"),
        ("Event Attribution",         "event_attribution_accuracy"),
        ("사건별 감정 집합 일치율",    "event_set_match"),
        ("· 귀속 판정 수",             "event_attribution_n"),
        ("· 집합 비교 사건 수",         "event_set_n"),
    ]
    th_head = ["IoU≥{}{}".format(th, "*" if th == IOU_DEFAULT else "") for th in IOU_THRESHOLDS]
    rows = []
    for label, key in event_rows:
        for i, v in enumerate(versions):
            rows.append([label if i == 0 else "", v]
                        + [fmt(results[v]["events"][th][key]) for th in IOU_THRESHOLDS])
    print_table("사건 — 근거 발화 IoU 정렬 (계획 §9.1). * 기본값. 볼 것은 임계값을 바꿔도 버전 순위가 "
                "유지되는가다", ["지표", "버전"] + th_head, rows, left=2)

    rows = []
    for name, _ in LAYERS:
        for i, v in enumerate(versions):
            r = results[v]["layers"][name]
            rows.append([name if i == 0 else "", v, fmt(r["n"]),
                         fmt(r["emotion_micro_f1"]), fmt(r["emotion_macro_f1"]),
                         fmt(r["jaccard"]), fmt(r["set_exact"]),
                         fmt(r["intensity_mae"]), fmt(r["intensity_response"]),
                         fmt(r["empty_hit"]), fmt(r["empty_miss"])])
    print_table("층별 — 세션 단위 감정·강도. 골드로 가른다. 층이 작으면 값이 크게 흔들린다 — n 과 함께 읽는다",
                ["층", "버전", "n", "Micro", "Macro", "Jaccard", "집합일치",
                 "강도MAE", "강도응답", "빈목록맞힘", "과잉보류"], rows, left=2)

    if per_emotion_for:
        r = results[per_emotion_for]["emotion"]
        rows = []
        for label in EMOTIONS:
            p, rc, f1 = r["per_emotion"][label]
            sup, pred_n = r["per_emotion_support"][label], r["per_emotion_predicted"][label]
            rows.append(["{} {}".format(KO[label], label), str(sup), str(pred_n),
                         fmt(p) if pred_n else "—", fmt(rc) if sup else "—",
                         fmt(f1) if sup or pred_n else "—"])
        print_table(f"감정별 성능 — {per_emotion_for} (지원 = 골드 세션 수, 예측 = 예측 세션 수)",
                    ["감정", "지원", "예측", "P", "R", "F1"], rows)

    print("\n※ Event Summary Score(BERTScore·사람 평가)·근거 적합률·신뢰도(ECE·Brier)는 자동 채점 "
          "대상이 아니다 — 앞의 둘은 별도 모델·사람이, 뒤의 것은 확률 출력이 필요하다(계획 §9).")


def main():
    ap = argparse.ArgumentParser(description="모델 비교 채점기 (정본 v2)")
    ap.add_argument("--gold", required=True, help="골드 라벨 JSON (taxonomy: v2)")
    ap.add_argument("--pred", action="append", required=True, metavar="이름=경로",
                    help="버전 예측 JSON. 여러 번 줄 수 있다 (예: v1=pred-v1.json)")
    ap.add_argument("--set", dest="subset",
                    help="골드의 set 으로 거른다 (natural / challenge / stratified). "
                         "Micro F1·Jaccard 는 challenge 를 빼고 봐야 한다(계획 §7.2·8.6)")
    ap.add_argument("--role", help="골드의 role 로 거른다 (AI Hub 표본: pilot / eval / safety / train)")
    ap.add_argument("--source", help="골드의 source 로 거른다 (real / synthetic / aihub). 섞어서 집계하지 않는다")
    ap.add_argument("--per-emotion", metavar="버전", help="이 버전의 감정별 P/R/F1 을 함께 낸다")
    args = ap.parse_args()

    gold = load(args.gold, "골드")
    filters = {"set": args.subset, "role": args.role, "source": args.source}
    results = {}
    for spec in args.pred:
        if "=" not in spec:
            sys.exit(f"--pred 는 '이름=경로' 형식이어야 합니다: {spec}")
        name, path = spec.split("=", 1)
        pairs, missing = build_pairs(gold, load(path, name), filters)
        if missing:
            print(f"[경고] {name}: 예측에 없는 세션 {len(missing)}개를 건너뜁니다 "
                  f"({', '.join(missing[:5])}{'…' if len(missing) > 5 else ''})", file=sys.stderr)
        if not pairs:
            sys.exit(f"{name}: 채점할 세션이 없습니다.")
        scored = [(g, p) for g, p in pairs if not blocked(g)]
        results[name] = {
            "emotion": score_emotions(scored),
            "layers": {layer: score_emotions([(g, p) for g, p in scored if keep(g)])
                       for layer, keep in LAYERS},
            "events": {th: score_events(pairs, th) for th in IOU_THRESHOLDS},
            "n": len(pairs),
            "excluded": len(pairs) - len(scored),
        }
        sources = Counter(g.get("source") for g, _ in pairs)
        if len(sources) > 1:
            print(f"[경고] {name}: source 가 섞여 있습니다 ("
                  + ", ".join(f"{s} {n}" for s, n in sources.items())
                  + ") — --source 로 나눠 보세요.", file=sys.stderr)

    if args.per_emotion and args.per_emotion not in results:
        sys.exit(f"--per-emotion {args.per_emotion}: 그런 버전이 없습니다 ({', '.join(results)})")

    scope = " · ".join(f"{k}={v}" for k, v in filters.items() if v) or "전체"
    print(f"\n채점 대상: {scope} · 세션 "
          + ", ".join(f"{v}={results[v]['n']}" for v in results)
          + " · 감정·강도 채점 제외(대표 감정 판단 어려움·체계 밖) "
          + ", ".join(f"{v}={results[v]['excluded']}" for v in results))
    report(results, args.per_emotion)


if __name__ == "__main__":
    main()
