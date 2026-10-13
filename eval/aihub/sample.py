#!/usr/bin/env python3
"""
층화 추출 — AI Hub 원문에서 파일럿·평가·학습 라벨링 후보를 뽑는다(계획 §7.4).

    python3 sample.py --dir "…/018.감성대화" --out ../data/sample-r1

뽑는 기준
  · 표현 유형(명시/간접)은 감정 어휘 사전으로 미리 가른다 — 층화의 첫 축
  · 각 층 안에서 감정이 고르게 나오도록, AI Hub 라벨을 12종으로 바꾼 값(proxy)으로 나눠 뽑는다.
    proxy 는 사람 판정과 25%만 맞으므로 "대략 고르게"일 뿐이다 — 1차 라벨링 후 모자란 감정을 겨냥해 2차를 뽑는다
  · 복합 감정은 미리 알 수 없다. 명시 쪽만 사전으로 "서로 다른 감정 단어 2개 이상"을 찾아 평가셋에 최소치를 넣는다
  · 인물 단위로 나눈다 — 평가 인물과 학습 인물은 겹치지 않고, 같은 인물은 전체에서 최대 2건
  · 표적 수집(seeds)에 쓰인 인물은 학습 쪽으로 보낸다

보류(감정 정보 부족) 사례는 AI Hub 에 거의 없어 여기서 뽑지 않는다 — 직접 쓴다.
산출물은 사람이 읽을 원문이므로 eval/data/ 아래에 둔다(커밋하지 않음).
"""

import argparse
import hashlib
import importlib.util
import json
import os
import random
import re
import sys
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
EMOTIONS = ["joy", "calm", "proud", "affection", "sad", "anxious", "angry",
            "disappointed", "ashamed", "confused", "sorry", "exhausted"]
KO = {"joy": "기쁨", "calm": "평온", "proud": "뿌듯함", "affection": "애정", "sad": "슬픔",
      "anxious": "불안", "angry": "분노", "disappointed": "실망", "ashamed": "부끄러움",
      "confused": "혼란", "sorry": "미안함", "exhausted": "지침"}


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def profile_of(split, talk_id):
    # talk-id = Pro_05349_00064 → 인물 Pro_05349. 학습·검증 사이에서 번호가 겹치므로 split 을 붙인다.
    return f"{split}:{'_'.join(talk_id.split('_')[:2])}"


def is_eval_profile(profile, mod=10):
    """인물 단위로 평가/학습을 가른다. 해시라 다시 돌려도 같은 인물이 같은 쪽에 간다."""
    return int(hashlib.md5(profile.encode("utf-8")).hexdigest(), 16) % mod == 0


def grams(text, n=3):
    t = re.sub(r"\s+", " ", text)
    return {t[i:i + n] for i in range(max(0, len(t) - n + 1))}


def too_close(g, refs, limit):
    """이미 뽑힌 것(refs) 중 하나라도 글자 3-gram Jaccard 가 limit 이상이면 True."""
    for h in refs:
        inter = len(g & h)
        if inter and inter / len(g | h) >= limit:
            return True
    return False


def allocate(groups, quota, picked, per_profile, cap, rng, refs, dup, topic_share):
    """
    감정별 묶음(groups: proxy → [row])에서 quota 를 최대한 고르게 뽑는다.

    다양성 제약 두 가지(오버피팅 방지 — diversity.py 로 확인):
      · 이미 뽑힌 대화(refs, 표적 수집 포함)와 거의 같으면 건너뛴다. AI Hub 는 같은 상황을
        다른 인물로 고쳐 쓴 대화가 있어 인물 분리만으로는 학습–평가 유출을 못 막는다
      · 한 감정 묶음 안에서 한 상황(AI Hub 상황 키워드)이 topic_share 를 넘지 않게 한다
    """
    classes = [c for c in EMOTIONS if groups.get(c)]
    pools = {c: rng.sample(groups[c], len(groups[c])) for c in classes}
    per_class = max(1, -(-quota // max(1, len(classes))))
    topic_cap = max(2, -(-int(per_class * topic_share * 100) // 100))
    topic_n = Counter()
    out, cursor = [], {c: 0 for c in classes}
    while len(out) < quota and classes:
        progressed = False
        for c in list(classes):
            if len(out) >= quota:
                break
            pool, i = pools[c], cursor[c]
            while i < len(pool):
                r = pool[i]; i += 1
                if r["key"] in picked or per_profile[r["profile"]] >= cap:
                    continue
                if topic_n[(c, r["aihub"]["topic"])] >= topic_cap:
                    continue
                g = r.setdefault("_g", grams(r["text"]))
                if too_close(g, refs, dup):
                    continue
                out.append(r); picked.add(r["key"]); per_profile[r["profile"]] += 1
                topic_n[(c, r["aihub"]["topic"])] += 1
                refs.append(g)
                progressed = True
                break
            cursor[c] = i
            if i >= len(pool):
                classes.remove(c)
        if not progressed:
            break
    return out


def record(r, role, assign=None, prefill=None):
    rec = {"id": r["key"], "role": role, "stratum": r["stratum"], "lex": r["lex"],
           "lexHits": r["hits"], "multiWord": r["multi"], "proxy": r["proxy"],
           "profile": r["profile"], "split": r["split"], "aihub": r["aihub"],
           "turns": [{"speaker": "user" if k.startswith("HS") else "assistant", "text": v}
                     for k, v in r["turns"]]}
    if assign:
        rec["assign"] = assign
    if prefill:
        rec["prefill"] = prefill
    return rec


def main():
    ap = argparse.ArgumentParser(description="층화 추출 — 파일럿·평가·학습 후보")
    ap.add_argument("--dir", required=True, help="018.감성대화 폴더")
    ap.add_argument("--out", required=True, help="산출 폴더 (eval/data/ 아래)")
    ap.add_argument("--seed", type=int, default=20261002)
    ap.add_argument("--seeds-file", default=os.path.join(HERE, "..", "data", "seeds.jsonl"))
    ap.add_argument("--eval-explicit", type=int, default=60)
    ap.add_argument("--eval-explicit-multi", type=int, default=20, help="평가 명시 중 감정 단어 2개 이상 최소치")
    ap.add_argument("--eval-indirect", type=int, default=120)
    ap.add_argument("--pilot-explicit", type=int, default=6)
    ap.add_argument("--pilot-indirect", type=int, default=14)
    ap.add_argument("--safety", type=int, default=50, help="학습 명시 안전판")
    ap.add_argument("--safety-from-seeds", type=int, default=5, help="안전판 중 표적 수집에서 가져올 애정·뿌듯함 각각")
    ap.add_argument("--train-indirect", type=int, default=250, help="학습 간접 1차")
    ap.add_argument("--cap", type=int, default=2, help="같은 인물 최대 건수")
    ap.add_argument("--dup", type=float, default=0.45, help="이미 뽑힌 것과 이 이상 비슷하면 건너뜀(글자 3-gram Jaccard)")
    ap.add_argument("--topic-share", type=float, default=0.4, help="한 감정 묶음에서 한 상황의 최대 비율")
    args = ap.parse_args()

    cv = _load("cv", os.path.join(HERE, "convert.py"))
    ck = _load("ck", os.path.join(HERE, "..", "lexicon", "check_lexicon.py"))
    _, ex, ev, wk = ck.load()
    mp = json.load(open(os.path.join(HERE, "mapping.json"), encoding="utf-8"))["map"]
    rng = random.Random(args.seed)

    rows = []
    for split in ("training", "validation"):
        for r in cv.read_split(args.dir, split):
            text = " ".join(v for k, v in r["turns"] if k.startswith("HS"))
            kind, hits = ck.classify(text, ex, ev, wk)
            proxy = mp.get(f"{r['major']}|{r['minor']}", [None])[0]
            emos = {h[1] for h in hits if h[2] in ("name", "colloquial")} if kind == "명시" else set()
            rows.append({
                "key": f"{split}_{r['id']}", "split": split, "profile": profile_of(split, r["id"]),
                "stratum": "명시" if kind == "명시" else "간접", "lex": kind,
                "hits": [f"{h[0]}→{h[1]}" for h in hits][:4], "multi": len(emos) >= 2,
                "proxy": proxy, "turns": r["turns"], "text": text,
                "aihub": {"major": r["major"], "minor": r["minor"], "age": r["age"], "topic": r["topic"]},
            })
    by_key = {r["key"]: r for r in rows}

    # 표적 수집 — 쓰인 대화와 인물을 기록하고, 그 인물은 학습 쪽으로 보낸다
    seeds = [json.loads(l) for l in open(args.seeds_file, encoding="utf-8") if l.strip()]
    def seed_key(s):
        sid = s.get("sourceId") or s["id"].split("_", 1)[1].rsplit("_", 2)[0]
        return f"{s['split']}_{sid}"
    seed_keys = {seed_key(s) for s in seeds}
    seed_profiles = {by_key[k]["profile"] for k in seed_keys if k in by_key}

    def side(r):
        return "eval" if (is_eval_profile(r["profile"]) and r["profile"] not in seed_profiles) else "train"

    picked, per_profile = set(seed_keys), Counter()
    refs = [grams(by_key[k]["text"]) for k in seed_keys if k in by_key]   # 표적 수집과도 겹치지 않게
    for k in seed_keys:
        if k in by_key:
            per_profile[by_key[k]["profile"]] += 1

    def groups(cond):
        g = defaultdict(list)
        for r in rows:
            if r["proxy"] and r["key"] not in picked and cond(r):
                g[r["proxy"]].append(r)
        return g

    out = defaultdict(list)
    # ── 평가: 명시(복합 최소치 먼저) · 간접 ──
    m = allocate(groups(lambda r: side(r) == "eval" and r["stratum"] == "명시" and r["multi"]),
                 args.eval_explicit_multi, picked, per_profile, args.cap, rng, refs, args.dup, args.topic_share)
    m += allocate(groups(lambda r: side(r) == "eval" and r["stratum"] == "명시"),
                  args.eval_explicit - len(m), picked, per_profile, args.cap, rng, refs, args.dup, args.topic_share)
    i = allocate(groups(lambda r: side(r) == "eval" and r["stratum"] == "간접"),
                 args.eval_indirect, picked, per_profile, args.cap, rng, refs, args.dup, args.topic_share)
    out["eval"] = [record(r, "eval", "AB") for r in m + i]

    # ── 파일럿 (학습 쪽 인물, 두 명 모두) ──
    p = allocate(groups(lambda r: side(r) == "train" and r["stratum"] == "명시"),
                 args.pilot_explicit, picked, per_profile, args.cap, rng, refs, args.dup, args.topic_share)
    p += allocate(groups(lambda r: side(r) == "train" and r["stratum"] == "간접"),
                  args.pilot_indirect, picked, per_profile, args.cap, rng, refs, args.dup, args.topic_share)
    out["pilot"] = [record(r, "pilot", "AB") for r in p]

    # ── 학습 명시 안전판: 애정·뿌듯함은 표적 수집에서, 나머지 9종은 AI Hub 명시에서 고르게 ──
    safety = []
    for emo in ("affection", "proud"):
        cand = [s for s in seeds if s.get("verdict") == [emo] or (emo in (s.get("verdict") or []) and len(s["verdict"]) == 1)]
        rng.shuffle(cand)
        taken, seen_match = 0, set()
        # 같은 표현만 반복되지 않게 걸린 표현(match)이 다른 것부터 고른다(애정이 '그리워'로만 채워지는 것 방지)
        cand.sort(key=lambda s: s.get("match", ""))
        cand = [s for s in cand if s.get("match") not in seen_match and not seen_match.add(s.get("match"))] + \
               [s for s in cand]
        used = set()
        for s in cand:
            k = seed_key(s)
            if taken >= args.safety_from_seeds or k not in by_key or k in used:
                continue
            used.add(k)
            if by_key[k]["stratum"] != "명시":      # 안전판은 감지기가 명시로 볼 대화만
                continue
            safety.append(record(by_key[k], "safety", prefill={"emotions": s["verdict"], "from": "harvest"}))
            taken += 1
    rest = allocate(groups(lambda r: side(r) == "train" and r["stratum"] == "명시"
                           and r["proxy"] not in ("affection", "proud")),
                    args.safety - len(safety), picked, per_profile, args.cap, rng, refs, args.dup, args.topic_share)
    safety += [record(r, "safety") for r in rest]
    out["safety"] = safety

    # ── 학습 간접 1차 ──
    t = allocate(groups(lambda r: side(r) == "train" and r["stratum"] == "간접"),
                 args.train_indirect, picked, per_profile, args.cap, rng, refs, args.dup, args.topic_share)
    out["train"] = [record(r, "train") for r in t]

    # 학습분은 두 라벨러에게 감정이 고르게 가도록 번갈아 배정한다
    for role in ("safety", "train"):
        by_p = defaultdict(list)
        for rec in out[role]:
            # 사람 판정이 있으면 그 감정으로, 없으면 AI Hub 대략값으로 묶는다(체계 밖이면 None)
            by_p[(rec.get("prefill") or {}).get("emotions", [rec["proxy"]])[0]].append(rec)
        n = 0
        for emo in EMOTIONS + [None]:
            for rec in by_p.get(emo, []):
                rec["assign"] = "A" if n % 2 == 0 else "B"; n += 1

    os.makedirs(args.out, exist_ok=True)
    for role, recs in out.items():
        rng.shuffle(recs)
        with open(os.path.join(args.out, f"{role}.jsonl"), "w", encoding="utf-8") as f:
            for rec in recs:
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")

    # ── 점검 ──
    eval_p = {r["profile"] for r in out["eval"]}
    train_p = {r["profile"] for role in ("pilot", "safety", "train") for r in out[role]}
    all_ids = [r["id"] for role in out for r in out[role]]
    checks = {
        "평가·학습 인물 겹침": len(eval_p & train_p),
        "중복 대화": len(all_ids) - len(set(all_ids)),
        "표적 수집과 평가 인물 겹침": len(eval_p & seed_profiles),
        "인물당 최대 건수(이번 추출)": max(Counter(r["profile"] for role in out for r in out[role]).values()),
    }
    manifest = {"seed": args.seed, "args": vars(args), "counts": {k: len(v) for k, v in out.items()},
                "checks": checks, "used_ids": sorted(set(all_ids))}
    json.dump(manifest, open(os.path.join(args.out, "manifest.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)

    print(f"AI Hub {len(rows):,}건 · 표적 수집 대화 {len(seed_keys)}건(인물 {len(seed_profiles)}명은 학습 쪽)\n")
    for role, title in (("pilot", "파일럿 (2명)"), ("eval", "평가 (2명)"),
                        ("safety", "학습 명시 안전판 (1명씩)"), ("train", "학습 간접 1차 (1명씩)")):
        recs = out[role]
        st = Counter(r["stratum"] for r in recs)
        pr = Counter((r.get("prefill") or {}).get("emotions", [r["proxy"]])[0] for r in recs)
        lx = Counter(r["lex"] for r in recs)
        print(f"■ {title} {len(recs)}건 — " + " · ".join(f"{k} {v}" for k, v in st.items())
              + (f" · 감정 단어 2개 이상 {sum(r['multiWord'] for r in recs)}" if st.get('명시') else ""))
        print("   감정(대략): " + ", ".join(f"{KO[e]} {pr[e]}" for e in EMOTIONS if pr[e])
              + (f"  (표적 수집 사람 판정 {sum(1 for r in recs if r.get('prefill'))}건 포함)" if any(r.get('prefill') for r in recs) else ""))
        if st.get("간접"):
            print("   간접 내역: " + ", ".join(f"{k} {v}" for k, v in lx.items() if k != "명시"))
        if role in ("safety", "train"):
            a = Counter(r.get("assign") for r in recs)
            print(f"   배정: A {a['A']} · B {a['B']}")
    print("\n점검: " + " · ".join(f"{k} {v}" for k, v in checks.items()))
    print(f"→ {args.out}/  (pilot · eval · safety · train .jsonl + manifest.json)")
    bad = checks["평가·학습 인물 겹침"] or checks["중복 대화"] or checks["표적 수집과 평가 인물 겹침"] \
        or checks["인물당 최대 건수(이번 추출)"] > args.cap
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
