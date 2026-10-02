#!/usr/bin/env python3
"""
다양성 점검 — 감정별로 뽑힌 대화가 너무 비슷하지 않은지 본다(오버피팅 방지).

    python3 diversity.py --sample ../data/sample-r1

세 가지를 본다.
  1. 거의 같은 대화 — 사용자 발화의 글자 3-gram Jaccard 가 높은 쌍. AI Hub 는 비슷한 상황을
     고쳐 쓴 대화가 있어 모델이 내용을 외울 수 있다
  2. 주제 쏠림 — 한 감정 묶음에서 특정 상황(AI Hub 상황 키워드)이나 단어가 지나치게 많은지.
     "부모님이 나오면 미안함" 같은 지름길을 배우게 된다
  3. 학습–평가 유출 — 인물은 나눴어도 내용이 거의 같은 대화가 양쪽에 있으면 평가가 부푼다

감정 묶음은 사람 판정(prefill)이 있으면 그것, 없으면 AI Hub 대략값(proxy)으로 나눈다 — 라벨링 전이라
대략이다. 라벨링 뒤에는 실제 정답 감정으로 다시 돌려야 한다.
"""

import argparse
import json
import os
import re
from collections import Counter, defaultdict
from itertools import combinations

KO = {"joy": "기쁨", "calm": "평온", "proud": "뿌듯함", "affection": "애정", "sad": "슬픔",
      "anxious": "불안", "angry": "분노", "disappointed": "실망", "ashamed": "부끄러움",
      "confused": "혼란", "sorry": "미안함", None: "체계 밖"}
JOSA = re.compile(r"(이랑|에서|에게|한테|으로|이나|까지|부터|처럼|보다|이|가|을|를|은|는|에|도|만|과|와|랑|로|의)$")
STOP = set("""너무 정말 진짜 그래서 그런데 그리고 하지만 내가 나는 나도 나를 나한테 내 우리 오늘 요즘 지금 이제
그냥 좀 많이 계속 다시 이번 같아 있어 없어 했어 해서 하고 하는 하면 그게 이게 저게 어떻게 무슨 어떤 그런 이런
것 거 수 때 때문 좋겠어 싶어 모르겠어 모르겠다 모르겠네 모르겠고 생각 마음 사람 하나 같은 정도 혹시 아직 벌써 언제 이렇게 그렇게 해야 할지 있는""".split())


def user_text(rec):
    return " ".join(t["text"] for t in rec["turns"] if t["speaker"] == "user")


def grams(s, n=3):
    s = re.sub(r"\s+", " ", s)
    return {s[i:i + n] for i in range(max(0, len(s) - n + 1))}


def jaccard(a, b):
    return len(a & b) / len(a | b) if a and b else 0.0


def words(s):
    out = set()
    for w in re.findall(r"[가-힣]{2,}", s):
        w = JOSA.sub("", w)
        if len(w) >= 2 and w not in STOP:
            out.add(w)
    return out


def label(rec):
    return (rec.get("prefill") or {}).get("emotions", [rec.get("proxy")])[0]


def main():
    ap = argparse.ArgumentParser(description="감정별 다양성 점검")
    ap.add_argument("--sample", required=True, help="sample.py 산출 폴더")
    ap.add_argument("--dup", type=float, default=0.45, help="거의 같은 대화로 볼 Jaccard 기준")
    ap.add_argument("--topic-share", type=float, default=0.5, help="한 상황 키워드가 이 비율을 넘으면 경고")
    ap.add_argument("--word-df", type=float, default=0.3, help="한 단어가 묶음의 이 비율 이상 + 다른 묶음 대비 2배 이상이면 경고")
    args = ap.parse_args()

    roles = {}
    for role in ("pilot", "eval", "safety", "train"):
        p = os.path.join(args.sample, f"{role}.jsonl")
        if os.path.exists(p):
            roles[role] = [json.loads(l) for l in open(p, encoding="utf-8") if l.strip()]
    for recs in roles.values():
        for r in recs:
            r["_g"] = grams(user_text(r)); r["_w"] = words(user_text(r))

    warn = []
    for role in ("train", "safety", "eval"):
        recs = roles.get(role, [])
        if not recs:
            continue
        groups = defaultdict(list)
        for r in recs:
            groups[label(r)].append(r)
        # 다른 묶음 대비 단어 빈도(쏠림 판단용)
        all_df = Counter(w for r in recs for w in r["_w"])
        print(f"\n━━ {role} {len(recs)}건 ━━")
        print(f"  {'감정':<6}{'건수':>4} {'평균유사':>7} {'최대유사':>7} {'근접쌍':>5}  {'최다 상황(비율)':<22} 쏠린 단어")
        for emo in sorted(groups, key=lambda e: (e is None, list(KO).index(e))):
            g = groups[emo]
            sims = [jaccard(a["_g"], b["_g"]) for a, b in combinations(g, 2)]
            near = [(a, b, s) for (a, b), s in zip(combinations(g, 2), sims) if s >= args.dup]
            topics = Counter(r["aihub"]["topic"] for r in g)
            t_name, t_n = topics.most_common(1)[0]
            df = Counter(w for r in g for w in r["_w"])
            skew = []
            for w, n in df.most_common(40):
                share = n / len(g)
                other = (all_df[w] - n) / max(1, len(recs) - len(g))
                if share >= args.word_df and share >= 2 * other and n >= 3:
                    skew.append(f"{w} {n}/{len(g)}")
            mean = sum(sims) / len(sims) if sims else 0
            mx = max(sims) if sims else 0
            print(f"  {KO[emo]:<6}{len(g):>4} {mean:>7.3f} {mx:>7.3f} {len(near):>5}  "
                  f"{t_name[:14]+f'({t_n/len(g):.0%})':<22} {', '.join(skew[:4]) or '—'}")
            if near:
                warn.append(("근접쌍", role, emo, near))
            if t_n / len(g) > args.topic_share:
                warn.append(("상황 쏠림", role, emo, f"{t_name} {t_n}/{len(g)}"))
            if skew:
                warn.append(("단어 쏠림", role, emo, skew[:4]))

    # 학습–평가 유출: 학습(train·safety·pilot) 대 평가의 최고 유사도
    train_side = roles.get("train", []) + roles.get("safety", []) + roles.get("pilot", [])
    leaks = []
    for e in roles.get("eval", []):
        best = max(train_side, key=lambda t: jaccard(e["_g"], t["_g"]), default=None)
        if best is not None:
            s = jaccard(e["_g"], best["_g"])
            if s >= args.dup:
                leaks.append((e, best, s))
    print(f"\n━━ 학습–평가 유출 (Jaccard ≥ {args.dup}) : {len(leaks)}쌍 ━━")
    for e, t, s in sorted(leaks, key=lambda x: -x[2])[:5]:
        print(f"  {s:.2f}  평가: {user_text(e)[:40]}")
        print(f"        학습: {user_text(t)[:40]}")

    print(f"\n━━ 경고 {len(warn)}건 ━━")
    for kind, role, emo, info in warn:
        if kind == "근접쌍":
            print(f"  [근접쌍] {role}·{KO[emo]} {len(info)}쌍")
            for a, b, s in info[:2]:
                print(f"      {s:.2f}  {user_text(a)[:36]}")
                print(f"            {user_text(b)[:36]}")
        else:
            print(f"  [{kind}] {role}·{KO[emo]}: {info}")
    if not warn and not leaks:
        print("  없음")


if __name__ == "__main__":
    main()
