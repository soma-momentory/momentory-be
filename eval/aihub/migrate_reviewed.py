#!/usr/bin/env python3
"""
판정 파일의 id 를 현재 후보 파일 기준으로 맞춘다.

`harvest.py` 의 id 규칙이 바뀌면(예: split 접두사 추가) 예전에 내보낸 판정 파일을 그대로
불러올 수 없다. 이 스크립트가 **문장으로 짝지어** id 를 다시 쓴다.

    python3 migrate_reviewed.py --reviewed ~/Downloads/harvest-reviewed.json \\
      --candidates ../data/harvest-candidates.jsonl --out ../data/harvest-resume.json

id 가 이미 맞으면 그대로 두고, 안 맞으면 `sentence` 로 찾는다. 문장이 여러 후보에 걸리면
**건너뛴다** — 잘못 붙이느니 다시 보는 편이 낫다.
"""

import argparse
import json
from collections import defaultdict


def main():
    ap = argparse.ArgumentParser(description="판정 파일 id 마이그레이션")
    ap.add_argument("--reviewed", required=True)
    ap.add_argument("--candidates", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    cands = [json.loads(l) for l in open(args.candidates, encoding="utf-8") if l.strip()]
    by_id = {c["id"]: c for c in cands}
    by_sent = defaultdict(list)
    for c in cands:
        by_sent[(c["sentence"], c["axis"])].append(c)

    d = json.load(open(args.reviewed, encoding="utf-8"))
    src = d.get("decisions") or {r["id"]: (r.get("verdict") or [r.get("primary")])
                                 for r in d.get("rows", [])}
    # 내보내기에 axis 가 없다 — id 꼬리(_affection / _proud)에서 뽑는다.
    def axis_of(rid, row):
        if row.get("axis"):
            return row["axis"]
        for a in ("affection", "proud"):
            if rid.endswith("_" + a):
                return a
        return None

    sent_of = {r["id"]: (r.get("sentence"), axis_of(r["id"], r)) for r in d.get("rows", [])}

    out, kept, moved, skipped = {}, 0, 0, []
    for rid, verdict in src.items():
        if not verdict:
            continue
        if rid in by_id:
            out[rid] = verdict
            kept += 1
            continue
        key = sent_of.get(rid)
        hits = by_sent.get(key, []) if key and key[0] else []
        if len(hits) == 1:
            out[hits[0]["id"]] = verdict
            moved += 1
        else:
            skipped.append((rid, len(hits)))

    json.dump({"reviewed": len(out), "total": len(cands), "kept": len(out),
               "decisions": out, "rows": []},
              open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    print(f"판정 {len(src)}건 → 복원 가능 {len(out)}건 (그대로 {kept} · id 재매핑 {moved})")
    if skipped:
        print(f"⚠ 건너뜀 {len(skipped)}건 — 문장으로 못 찾았거나 여러 후보에 걸림:")
        for rid, n in skipped[:5]:
            print(f"    {rid}  (후보 {n}개)")
    lost = (d.get("reviewed") or 0) - len(src)
    if lost > 0:
        print(f"ℹ 원본에 '해당 없음' {lost}건이 담겨 있지 않아 다시 보게 됩니다"
              f" (옛 내보내기 형식).")
    print(f"\n→ {args.out}\n  harvest.html 에서 후보를 불러온 뒤 이 파일을 불러오세요.")


if __name__ == "__main__":
    main()
