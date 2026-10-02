#!/usr/bin/env python3
"""
표적 수집 결과를 **하나로 합친다.**

판정이 여러 번에 걸쳐 내보내졌고 그 사이에 두 가지가 바뀌었다:

  · **id 규칙** — split 접두사를 붙였다(AI Hub talk-id 가 training/validation 에서 겹쳐서).
    옛 파일은 id 가 안 맞으므로 **문장으로 짝지어** 되살린다.
  · **후보 목록** — 수확이 낮은 표현(사랑·좋아해 계열)을 뺐다. 그때 빠진 후보에 달린 판정도
    **버리지 않는다** — "이 문장은 애정이 아니었다"는 것도 어휘 사전의 결과다.

그래서 합칠 때는 **전체 후보**(`harvest-candidates-full.jsonl`)를 기준으로 맞춘다.

    python3 consolidate.py --candidates ../data/harvest-candidates-full.jsonl \\
      --reviewed ~/Downloads/harvest-reviewed*.json ../data/harvest-reviewed.json \\
      --out ../data/seeds.jsonl --report

같은 문장이 여러 파일에 있으면 **나중 파일(수정 시각 기준)이 이긴다.**
"""

import argparse
import glob
import json
import os
from collections import Counter, defaultdict

KO = {"joy": "기쁨", "calm": "평온", "proud": "뿌듯함", "affection": "애정", "sad": "슬픔",
      "anxious": "불안", "angry": "분노", "disappointed": "실망", "ashamed": "부끄러움",
      "confused": "혼란", "sorry": "미안함",
      "out": "체계 밖", "unsure": "애매", "none": "해당 없음"}
ORDER = ["joy", "calm", "proud", "affection", "sad", "anxious", "angry",
         "disappointed", "ashamed", "confused", "sorry", "out", "unsure", "none"]
SIT = {
    "진로,취업,직장": "일·학업", "학업 및 진로": "일·학업", "직장, 업무 스트레스": "일·학업",
    "가족관계": "가까운 관계", "연애,결혼,출산": "가까운 관계", "대인관계(부부, 자녀)": "가까운 관계",
    "대인관계": "느슨한 관계", "학교폭력/따돌림": "느슨한 관계",
    "건강": "몸·건강", "건강,죽음": "몸·건강",
    "재정": "돈·생활", "재정,은퇴,노후준비": "돈·생활", "해당없음": "기타",
}
COLS = ["일·학업", "가까운 관계", "느슨한 관계", "몸·건강", "돈·생활", "기타"]


def load_reviewed(path):
    d = json.load(open(path, encoding="utf-8"))
    dec = d.get("decisions")
    if dec:
        return {i: v for i, v in dec.items() if v}, {}
    # 옛 형식 — rows 만 있고 '해당 없음'은 담겨 있지 않다
    out, sent = {}, {}
    for r in d.get("rows") or []:
        v = r.get("verdict") or ([r["primary"]] if r.get("primary") else [])
        if not v:
            continue
        out[r["id"]] = v
        if r.get("sentence"):
            axis = next((a for a in ("affection", "proud") if r["id"].endswith("_" + a)), None)
            sent[r["id"]] = (r["sentence"], axis)
    return out, sent


def main():
    ap = argparse.ArgumentParser(description="표적 수집 판정 합치기")
    ap.add_argument("--candidates", required=True, help="전체 후보 JSONL (full)")
    ap.add_argument("--reviewed", nargs="+", required=True, help="판정 파일들 (glob 가능)")
    ap.add_argument("--out", help="합친 결과를 쓸 JSONL")
    ap.add_argument("--report", action="store_true", help="요약 표를 낸다")
    args = ap.parse_args()

    cands = [json.loads(l) for l in open(args.candidates, encoding="utf-8") if l.strip()]
    by_id = {c["id"]: c for c in cands}
    by_sent = defaultdict(list)
    for c in cands:
        by_sent[(c["sentence"], c["axis"])].append(c)

    paths = []
    for pat in args.reviewed:
        paths += glob.glob(os.path.expanduser(pat))
    paths = sorted(set(paths), key=os.path.getmtime)          # 오래된 것부터 → 나중이 덮는다
    if not paths:
        raise SystemExit("판정 파일을 찾지 못했습니다.")

    merged, source, stats = {}, {}, []
    for p in paths:
        dec, sent = load_reviewed(p)
        kept = moved = skipped = 0
        for rid, v in dec.items():
            if rid in by_id:
                merged[rid] = v; source[rid] = p; kept += 1
                continue
            hits = by_sent.get(sent.get(rid), [])             # 옛 id → 문장으로 찾는다
            if len(hits) == 1:
                merged[hits[0]["id"]] = v; source[hits[0]["id"]] = p; moved += 1
            else:
                skipped += 1
        stats.append((os.path.basename(p), len(dec), kept, moved, skipped))

    print(f"판정 파일 {len(paths)}개 → 합친 판정 {len(merged)}건\n")
    print(f"  {'파일':<28}{'판정':>6}{'그대로':>7}{'id재매핑':>9}{'건너뜀':>7}")
    for name, n, k, m, s in stats:
        print(f"  {name:<28}{n:>6}{k:>7}{m:>9}{s:>7}")

    rows = []
    for rid, v in merged.items():
        c = by_id[rid]
        rows.append({
            "id": rid, "verdict": v, "primary": v[0], "taxonomy": "v2",
            "source": "aihub-harvest", "sentence": c["sentence"], "match": c["match"],
            "situation": SIT.get(c["aihub"]["topic"], "기타"),
            "context": c["context"], "aihub": c["aihub"], "split": c["split"],
        })
    rows.sort(key=lambda r: (ORDER.index(r["primary"]) if r["primary"] in ORDER else 99,
                             r["match"], r["sentence"]))

    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(f"\n{len(rows)}건 → {args.out}")

    if not args.report:
        return

    cnt = Counter(v for r in rows for v in r["verdict"])
    print("\n── 감정별 ──")
    for k in ORDER:
        if cnt[k]:
            print(f"  {KO[k]:<7}{cnt[k]:>5}")

    print("\n── 감정 × 상황 (씨앗으로 쓸 11종만) ──")
    grid = defaultdict(Counter)
    for r in rows:
        for v in r["verdict"]:
            if v in ORDER[:11]:
                grid[v][r["situation"]] += 1
    print(f"  {'감정':<7}" + "".join(f"{c:>9}" for c in COLS) + f"{'계':>6}")
    for e in ORDER[:11]:
        row = grid[e]
        print(f"  {KO[e]:<7}" + "".join(f"{(row[c] or '·'):>9}" for c in COLS)
              + f"{sum(row.values()):>6}")

    print("\n── 애정·뿌듯함의 표현 쏠림 ──")
    for axis in ("affection", "proud"):
        got = [r for r in rows if axis in r["verdict"]]
        m = Counter(r["match"] for r in got)
        top = m.most_common(3)
        share = sum(n for _, n in top) / len(got) if got else 0
        print(f"  {KO[axis]:<5} {len(got):>4}건 · 상위 3표현이 {share:.0%} "
              f"({', '.join(f'{k} {n}' for k, n in top)})")


if __name__ == "__main__":
    main()
