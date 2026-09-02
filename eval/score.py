#!/usr/bin/env python3
"""
모델 비교 채점기 (모델 비교 계획 §7).

골드 라벨과 한 개 이상 버전의 예측을 받아 동일한 기준으로 채점하고 성능표를 낸다.
**모델에 무관하다** — 예측이 규칙(V0)에서 왔든 LLM(V1)에서 왔든 파인튜닝 모델(V2/V3)에서
왔든, 같은 JSON 모양이기만 하면 같은 잣대로 잰다. 그게 이 파일이 존재하는 이유다.

사용법:
    python3 score.py --gold gold.json --pred v0=pred-v0.json --pred v1=pred-v1.json

의존성 없음(표준 라이브러리만). numpy·sklearn 없이 돌게 두어 어디서든 바로 실행된다.
"""

import argparse
import json
import sys
from collections import defaultdict
from itertools import permutations

# 도메인과 같은 고정 목록 — Emotion.java / EmotionPhase.java 와 일치해야 한다.
EMOTIONS = ["anxious", "depressed", "angry", "frustrated", "happy",
            "stuck", "lethargic", "tired", "proud", "calm"]
PHASES = ["before", "during", "after", "now"]
INTENSITY_LEVELS = 5  # 0~4

# 사건 정렬 IoU 임계값 (계획 §7.1) — 하나로 고정하지 않고 민감도를 함께 본다.
IOU_THRESHOLDS = [0.3, 0.5, 0.7]


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
    (10종 중 6종이 미등장이면 다 맞혀도 0.4 가 최대). 예측만 있고 골드에 없는 라벨은 남겨서
    (F1=0) 없는 감정을 지어낸 것에 벌점이 가게 한다.

    :return: (macro F1, 평균에 들어간 라벨 수) — 라벨 수를 함께 봐야 값을 해석할 수 있다.
    """
    used = [l for l in labels if counts[l][0] + counts[l][1] + counts[l][2] > 0]
    if not used:
        return None, 0
    return sum(prf(*counts[l])[2] for l in used) / len(used), len(used)


def micro_f1(counts, labels):
    tp = sum(counts[l][0] for l in labels)
    fp = sum(counts[l][1] for l in labels)
    fn = sum(counts[l][2] for l in labels)
    return prf(tp, fp, fn)[2]


def quadratic_weighted_kappa(gold, pred, k=INTENSITY_LEVELS):
    """순서형 일치도 — 1칸 틀린 것과 3칸 틀린 것을 다르게 벌한다."""
    n = len(gold)
    if n == 0:
        return None
    observed = [[0] * k for _ in range(k)]
    hist_g, hist_p = [0] * k, [0] * k
    for g, p in zip(gold, pred):
        observed[g][p] += 1
        hist_g[g] += 1
        hist_p[p] += 1
    weight = [[((i - j) ** 2) / ((k - 1) ** 2) for j in range(k)] for i in range(k)]
    num = sum(weight[i][j] * observed[i][j] for i in range(k) for j in range(k))
    den = sum(weight[i][j] * hist_g[i] * hist_p[j] / n for i in range(k) for j in range(k))
    return 1 - num / den if den else None


def mean(xs):
    return sum(xs) / len(xs) if xs else None


# ── 사건 정렬 (계획 §7.1) ────────────────────────────────────────────────

def iou(a, b):
    """발화 번호 집합의 교집합 ÷ 합집합. 넉넉히 담아도 적게 담아도 점수가 깎인다."""
    sa, sb = set(a or []), set(b or [])
    if not sa and not sb:
        return 0.0
    union = sa | sb
    return len(sa & sb) / len(union) if union else 0.0


def align_events(gold_events, pred_events, threshold):
    """
    IoU 합이 최대가 되는 이분 매칭을 찾고, 임계값 미만인 짝은 파기한다.

    각자 제일 좋아하는 짝을 순서대로 집는 그리디는 전체 최적이 아닐 수 있어 조합을 다 본다.
    사건이 최대 2개라 경우의 수가 몇 개 안 된다.

    :return: (matched, unmatched_gold, unmatched_pred)
             matched 는 [(gold_event, pred_event, iou)]
    """
    if not gold_events or not pred_events:
        return [], list(gold_events), list(pred_events)

    best, best_score = None, -1.0
    short, long_ = (gold_events, pred_events) if len(gold_events) <= len(pred_events) \
        else (pred_events, gold_events)
    gold_first = short is gold_events

    for combo in permutations(range(len(long_)), len(short)):
        pairs, score = [], 0.0
        for si, li in enumerate(combo):
            g, p = (short[si], long_[li]) if gold_first else (long_[li], short[si])
            v = iou(g.get("evidence"), p.get("evidence"))
            pairs.append((g, p, v))
            score += v
        if score > best_score:
            best, best_score = pairs, score

    matched = [pair for pair in best if pair[2] >= threshold]
    matched_gold = {id(g) for g, _, _ in matched}
    matched_pred = {id(p) for _, p, _ in matched}
    return (matched,
            [g for g in gold_events if id(g) not in matched_gold],
            [p for p in pred_events if id(p) not in matched_pred])


# ── 감정 (세션 단위 — 정렬과 무관, 계획 §7) ──────────────────────────────

def self_emotions(session):
    """골드는 타인 감정을 experiencer='other' 로 표시할 수 있다. 자기 감정만 채점 대상."""
    return [e for e in session.get("emotions", [])
            if e.get("experiencer", "self") == "self" and e.get("normalized")]


def other_emotions(session):
    return [e for e in session.get("emotions", [])
            if e.get("experiencer") == "other" and e.get("normalized")]


def strongest_by_label(emotions):
    """
    같은 감정이 한 세션에 여러 번 나오면 <b>가장 강한 것</b>으로 접는다.

    강도·시점을 한 쌍씩 비교하려면 (세션, 감정) 하나에 값이 하나여야 한다. 여러 번 표현된
    감정에서 사용자·리포트가 대표로 삼는 것은 가장 강한 표현이라고 보고 그쪽을 남긴다.
    (파일럿에서 이 선택이 타당한지 다시 본다 — 계획 §10)
    """
    best = {}
    for e in emotions:
        label = e["normalized"]
        cur = best.get(label)
        if cur is None or (e.get("intensity") or -1) > (cur.get("intensity") or -1):
            best[label] = e
    return best


def score_emotions(pairs):
    """pairs: [(gold_session, pred_session)] → 감정 집합·강도·시점 지표."""
    counts = {l: [0, 0, 0] for l in EMOTIONS}          # label -> [tp, fp, fn]
    jaccards, count_errors = [], []
    g_int, p_int = [], []
    phase_counts = {p: [0, 0, 0] for p in PHASES}
    false_self, other_total = 0, 0

    for gold, pred in pairs:
        g_by = strongest_by_label(self_emotions(gold))
        p_by = strongest_by_label(
            [e for e in pred.get("emotions", []) if e.get("normalized")])
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
        count_errors.append(abs(len(p_set) - len(g_set)))

        for label in g_set & p_set:
            gi, pi = g_by[label].get("intensity"), p_by[label].get("intensity")
            if gi is not None and pi is not None:
                g_int.append(gi)
                p_int.append(pi)
            gp, pp = g_by[label].get("phase"), p_by[label].get("phase")
            if gp in PHASES and pp in PHASES:
                if gp == pp:
                    phase_counts[gp][0] += 1
                else:
                    phase_counts[pp][1] += 1
                    phase_counts[gp][2] += 1

        # 타인 감정을 사용자 감정으로 잡았는가 — 골드 자기 감정에 없는데 예측에 있으면 오탐.
        for e in other_emotions(gold):
            other_total += 1
            if e["normalized"] not in g_set and e["normalized"] in p_set:
                false_self += 1

    n = len(pairs)
    return {
        "emotion_macro_f1": macro_f1(counts, EMOTIONS)[0],
        "emotion_labels_scored": macro_f1(counts, EMOTIONS)[1],
        "emotion_micro_f1": micro_f1(counts, EMOTIONS),
        "jaccard": mean(jaccards),
        "label_count_error": mean(count_errors),
        "hamming_loss": sum(c[1] + c[2] for c in counts.values()) / (n * len(EMOTIONS)) if n else None,
        "intensity_mae": mean([abs(a - b) for a, b in zip(g_int, p_int)]),
        "intensity_qwk": quadratic_weighted_kappa(g_int, p_int),
        "intensity_pairs": len(g_int),
        "phase_macro_f1": macro_f1(phase_counts, PHASES)[0],
        "false_self_rate": (false_self / other_total) if other_total else None,
        "per_emotion": {l: prf(*counts[l]) for l in EMOTIONS},
        "per_emotion_support": {l: counts[l][0] + counts[l][2] for l in EMOTIONS},
    }


# ── 사건 (정렬 필요) ─────────────────────────────────────────────────────

def score_events(pairs, threshold):
    det = [0, 0, 0]                                   # tp, fp, fn
    count_hits, summary_missing = 0, 0
    turn_counts = defaultdict(lambda: [0, 0, 0])      # class -> [tp, fp, fn]
    attr_correct, attr_total = 0, 0

    for gold, pred in pairs:
        g_events = gold.get("events", [])
        p_events = pred.get("events", [])
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
        p_by_label = defaultdict(list)
        for e in pred.get("emotions", []):
            if e.get("normalized"):
                p_by_label[e["normalized"]].append(e)
        for e in self_emotions(gold):
            if e.get("eventId") is None:
                continue
            candidates = p_by_label.get(e["normalized"])
            if not candidates:
                continue                              # 감정 자체를 못 맞힘 — 감정 F1 이 이미 벌한다
            attr_total += 1
            if any(pred_to_gold.get(c.get("eventId")) == e["eventId"] for c in candidates):
                attr_correct += 1

    classes = sorted(turn_counts, key=str)
    return {
        "event_count_accuracy": count_hits / len(pairs) if pairs else None,
        "event_detection_f1": prf(*det)[2],
        "event_detection_pr": prf(*det)[:2],
        "turn_assignment_macro_f1": macro_f1(turn_counts, classes)[0],
        "event_attribution_accuracy": (attr_correct / attr_total) if attr_total else None,
        "event_attribution_n": attr_total,
        "summary_missing": summary_missing,
    }


# ── 입출력 ───────────────────────────────────────────────────────────────

def load(path):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    return {s["sessionId"]: s for s in data}


def build_pairs(gold, pred, subset=None):
    pairs, missing = [], []
    for sid, g in gold.items():
        if subset and g.get("set") != subset:
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


def report(results, per_emotion_for=None):
    versions = list(results)
    rows = [
        ("감정 Macro F1",        "emotion_macro_f1",         "emotion"),
        ("감정 Micro F1",        "emotion_micro_f1",         "emotion"),
        ("Jaccard",              "jaccard",                  "emotion"),
        ("라벨 개수 오차 ↓",     "label_count_error",        "emotion"),
        ("Hamming Loss ↓",       "hamming_loss",             "emotion"),
        ("강도 MAE ↓",           "intensity_mae",            "emotion"),
        ("강도 QWK",             "intensity_qwk",            "emotion"),
        ("시점 Macro F1",        "phase_macro_f1",           "emotion"),
        ("타인→자기 오탐률 ↓",   "false_self_rate",          "emotion"),
        ("· 채점한 감정 라벨 수",  "emotion_labels_scored",    "emotion"),
        ("· 강도 비교 쌍 수",      "intensity_pairs",          "emotion"),
    ]
    width = max(len(r[0]) for r in rows) + 2
    col = max(12, max(len(v) for v in versions) + 2)

    print("\n" + "=" * (width + col * len(versions)))
    print("세션 단위 지표 — 사건 정렬과 무관 (계획 §7)")
    print("=" * (width + col * len(versions)))
    print("지표".ljust(width) + "".join(v.rjust(col) for v in versions))
    print("-" * (width + col * len(versions)))
    for label, key, group in rows:
        line = label.ljust(width)
        for v in versions:
            line += fmt(results[v][group][key]).rjust(col)
        print(line)

    print("\n" + "=" * (width + col * len(versions)))
    print("사건 지표 — IoU 임계값별 (계획 §7.1). 볼 것은 절대값이 아니라 순위가 유지되는가다.")
    print("=" * (width + col * len(versions)))
    event_rows = [
        ("사건 개수 정확도",     "event_count_accuracy"),
        ("Event Detection F1",   "event_detection_f1"),
        ("Turn Assignment F1",   "turn_assignment_macro_f1"),
        ("Event Attribution",    "event_attribution_accuracy"),
        ("· 귀속 판정 수",        "event_attribution_n"),
    ]
    for th in IOU_THRESHOLDS:
        print(f"\n[IoU ≥ {th}]")
        print("지표".ljust(width) + "".join(v.rjust(col) for v in versions))
        print("-" * (width + col * len(versions)))
        for label, key in event_rows:
            line = label.ljust(width)
            for v in versions:
                line += fmt(results[v]["events"][th][key]).rjust(col)
            print(line)

    if per_emotion_for:
        r = results[per_emotion_for]["emotion"]
        print(f"\n{'=' * 52}\n감정별 성능 — {per_emotion_for}\n{'=' * 52}")
        print(f"{'감정':<12}{'지원':>6}{'P':>9}{'R':>9}{'F1':>9}")
        print("-" * 52)
        for label in EMOTIONS:
            p, rc, f1 = r["per_emotion"][label]
            print(f"{label:<12}{r['per_emotion_support'][label]:>6}"
                  f"{fmt(p):>9}{fmt(rc):>9}{fmt(f1):>9}")

    print("\n※ Event Summary Score(BERTScore·사람 평가)와 신뢰도(ECE·Brier)는 자동 채점 대상이 "
          "아니다 — 전자는 별도 모델이, 후자는 확률 출력이 필요하다(계획 §7).")


def main():
    ap = argparse.ArgumentParser(description="모델 비교 채점기")
    ap.add_argument("--gold", required=True, help="골드 라벨 JSON")
    ap.add_argument("--pred", action="append", required=True, metavar="이름=경로",
                    help="버전 예측 JSON. 여러 번 줄 수 있다 (예: v1=pred-v1.json)")
    ap.add_argument("--set", dest="subset", choices=["natural", "challenge"],
                    help="골드의 set 필드로 거른다. Micro F1·Jaccard 는 natural 만 보는 게 맞다(계획 §5.2)")
    ap.add_argument("--per-emotion", metavar="버전", help="이 버전의 감정별 P/R/F1 을 함께 낸다")
    args = ap.parse_args()

    gold = load(args.gold)
    results = {}
    for spec in args.pred:
        if "=" not in spec:
            sys.exit(f"--pred 는 '이름=경로' 형식이어야 합니다: {spec}")
        name, path = spec.split("=", 1)
        pairs, missing = build_pairs(gold, load(path), args.subset)
        if missing:
            print(f"[경고] {name}: 예측에 없는 세션 {len(missing)}개를 건너뜁니다 "
                  f"({', '.join(missing[:5])}{'…' if len(missing) > 5 else ''})", file=sys.stderr)
        if not pairs:
            sys.exit(f"{name}: 채점할 세션이 없습니다.")
        results[name] = {
            "emotion": score_emotions(pairs),
            "events": {th: score_events(pairs, th) for th in IOU_THRESHOLDS},
            "n": len(pairs),
        }

    scope = f"set={args.subset}" if args.subset else "전체"
    print(f"\n채점 대상: {scope} · 세션 "
          + ", ".join(f"{v}={results[v]['n']}" for v in results))
    report(results, args.per_emotion)


if __name__ == "__main__":
    main()
