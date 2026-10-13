#!/usr/bin/env python3
"""
평가셋에서 2인 독립 라벨링할 일부를 고른다 — 나머지는 1인이 한다(2026-10-13 결정).

    python3 pick_double.py --sample ../data/sample-r1 --n 60

고르는 방법.
  · 평가셋의 층 비율을 그대로 따른다(명시 1/3 · 간접 2/3). 층 안에서는 AI Hub 대략값(proxy)으로 감정을 고르게.
  · 이미 고른 것과 **가장 덜 비슷한** 대화부터 고른다(먼 점 고르기). 비슷함은 사용자 발화의 글자 3-gram
    Jaccard 와 단어 Jaccard 중 큰 쪽 — 글자로는 달라도 "이혼"·"승진" 같은 소재가 겹치면 잡는다.
    같은 상황 키워드·연령대가 겹치면 조금 깎는다.
  · 지침(2026-10-13 신설)은 AI Hub 에 대응 소분류가 없어 proxy 로는 안 잡힌다. 어휘 사전으로 지침 단어가
    든 대화를 먼저 --exhausted 건 넣어 새 감정의 일치도도 잴 수 있게 한다.

결과는 manifest.json 의 "double"(고른 id)과 "double_args" 에 적고 eval.jsonl 의 assign 을 바꾼다
(고른 것 "AB", 나머지 "A"). --dry-run 이면 점검표만 낸다.
"""

import argparse
import json
import os
import random
import re
import sys
from collections import Counter
from itertools import combinations

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "lexicon"))
import check_lexicon as cl  # noqa: E402

from diversity import user_text, grams, jaccard, words  # noqa: E402


def exhausted_hits(text, explicit):
    return [m.group(0) for rx, k, _ in explicit if "exhausted" in k.split("/") for m in [rx.search(text)] if m]


def quotas(recs, n):
    """층 비율 → 층 안에서 proxy 비율. 최대 나머지 방식으로 합을 맞춘다."""
    def split(total, counts):
        raw = {k: total * v / sum(counts.values()) for k, v in counts.items()}
        q = {k: int(v) for k, v in raw.items()}
        for k in sorted(raw, key=lambda k: raw[k] - q[k], reverse=True)[: total - sum(q.values())]:
            q[k] += 1
        return q
    by_stratum = split(n, Counter(r["stratum"] for r in recs))
    out = {}
    for st, k in by_stratum.items():
        for px, m in split(k, Counter(r["proxy"] for r in recs if r["stratum"] == st)).items():
            out[(st, px)] = m
    return out


def pick(recs, n, n_exhausted, explicit):
    g = {r["id"]: grams(user_text(r)) for r in recs}
    w = {r["id"]: words(user_text(r)) for r in recs}

    def sim(a, b):
        return max(jaccard(g[a], g[b]), jaccard(w[a], w[b]))
    quota = quotas(recs, n)
    chosen, topic, age = [], Counter(), Counter()

    def score(r):
        far = min((1 - sim(r["id"], c["id"]) for c in chosen), default=1.0)
        return far - 0.02 * topic[r["aihub"]["topic"]] - 0.01 * age[r["aihub"]["age"]]

    def take(r):
        chosen.append(r)
        quota[(r["stratum"], r["proxy"])] -= 1
        topic[r["aihub"]["topic"]] += 1
        age[r["aihub"]["age"]] += 1

    ex = [r for r in recs if exhausted_hits(user_text(r), explicit)]
    for _ in range(min(n_exhausted, len(ex))):
        cand = [r for r in ex if r not in chosen and quota[(r["stratum"], r["proxy"])] > 0]
        if cand:
            take(max(cand, key=score))
    while len(chosen) < n:
        open_ = [k for k, v in quota.items() if v > 0]
        cand = [r for r in recs if r not in chosen and (r["stratum"], r["proxy"]) in open_]
        take(max(cand, key=lambda r: (score(r), r["id"])))
    return chosen


def report(name, rs):
    g = [(grams(user_text(r)), words(user_text(r))) for r in rs]
    sims = [max(jaccard(a[0], b[0]), jaccard(a[1], b[1])) for a, b in combinations(g, 2)]
    top = Counter(r["aihub"]["topic"] for r in rs).most_common(1)[0]
    return (f"{name:12} 평균 유사도 {sum(sims) / len(sims):.3f} · 최대 {max(sims):.3f} · "
            f"0.2 이상 쌍 {sum(s >= 0.2 for s in sims)} · 상황 {len(set(r['aihub']['topic'] for r in rs))}종"
            f"(최다 {top[0]} {top[1]})")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--sample", required=True, help="sample.py 산출 폴더")
    ap.add_argument("--n", type=int, default=60)
    ap.add_argument("--exhausted", type=int, default=2, help="지침 단어가 든 대화를 최소 몇 건 넣을지")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    path = os.path.join(args.sample, "eval.jsonl")
    recs = [json.loads(l) for l in open(path, encoding="utf-8")]
    _, explicit, _, _ = cl.load()
    chosen = pick(recs, args.n, args.exhausted, explicit)
    ids = {r["id"] for r in chosen}

    print(f"평가 {len(recs)}건 중 2인 {len(chosen)}건")
    print("  층:", dict(Counter(r["stratum"] for r in chosen)), "· 감정단어 2개+:", sum(r["multiWord"] for r in chosen))
    print("  proxy:", dict(Counter(r["proxy"] for r in chosen)))
    print("  연령:", dict(Counter(r["aihub"]["age"] for r in chosen)))
    print("  지침 단어:", [(r["id"], exhausted_hits(user_text(r), explicit)) for r in chosen if exhausted_hits(user_text(r), explicit)])
    print(report("고른 것", chosen))
    rng = random.Random(0)
    base = [report("무작위", rng.sample(recs, args.n)) for _ in range(3)]
    print("\n".join(base))
    print(report("평가 전체", recs))
    if args.dry_run:
        return

    with open(path, "w", encoding="utf-8") as f:
        for r in recs:
            r["assign"] = "AB" if r["id"] in ids else "A"
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    mpath = os.path.join(args.sample, "manifest.json")
    m = json.load(open(mpath, encoding="utf-8"))
    m["double"] = sorted(ids)
    m["double_args"] = {"n": args.n, "exhausted": args.exhausted, "picked_by": "pick_double.py"}
    json.dump(m, open(mpath, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"\n→ {path} assign 갱신(AB {len(ids)} · A {len(recs) - len(ids)}), manifest.json 에 double 기록")


if __name__ == "__main__":
    main()
