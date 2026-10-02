#!/usr/bin/env python3
"""
표적 수집 — AI Hub 018 텍스트에서 **애정·뿌듯함** 후보 발화를 긁는다.

계획 §6.1 실측: 이 코퍼스는 애정 라벨이 0건, 뿌듯함 direct 0건이다. 그런데 **표현은 있다** —
라벨 체계에 자리가 없어 슬픔·기쁨으로 흡수돼 있을 뿐이다. 그 표현을 찾아내는 것이 이 도구다.

**AI Hub 라벨은 쓰지 않는다.** 이 코퍼스의 라벨은 작성자에게 *지시된* 감정이라 발화에 드러난
감정과 느슨하게 붙어 있고(실측: `기쁨|편안한` 3건 중 2건이 우리 기준으로 기쁨), 정본 §8.1의
"라벨의 근거는 사용자 발화에서 찾는다"와 기준이 다르다. 여기서는 **텍스트만** 본다.

**재현율 우선.** 정규식은 넓게 긁고 정밀도는 사람이 `harvest.html` 에서 만든다. 놓친 것은
되찾을 수 없지만 잘못 긁은 것은 한 번 눌러 버리면 된다.

    python3 harvest.py --dir "…/018.감성대화" --out ../data/harvest-candidates.jsonl

산출물은 v2 골드가 아니다. **감정 어휘 사전(V0·§5.1)의 재료**이자 **자체 생성 세트의 씨앗**
(§7.3)이다. 강도·사건·근거가 없다.
"""

import argparse
import importlib.util
import json
import os
import re
import sys
from collections import Counter

# 넓게 긁는다 — 사람이 거른다. 괄호 그룹을 쓰지 않아야 finditer 로 전체 매치를 잡는다.
PATTERNS = {
    "affection": re.compile(
        r"그리워|그립[다더은는을]|그리운|그리움|"
        r"애틋|사랑|설레|설렘|정겨운|정겹|반했|반해서|"
        r"[을를이가]\s*보고\s?싶|보고싶|"          # 「이야기해보고 싶」류를 피하려고 조사에 붙인다
        r"좋아하는 (?:사람|사이)|좋아해|좋아했|"
        r"아끼는 (?:사람|친구|후배|동생)|각별|"
        r"[가이]\s*생각이 나|생각이 많이 나"),      # 「그 생각이 나서」 같은 사건 회상은 뺀다
    "proud": re.compile(
        r"뿌듯|보람|자랑스[럽러운]|자랑거리|성취감|"
        r"해냈|해내[서고]|이뤄냈|이루어냈|대견|"
        r"스스로[가도]? ?(?:대단|기특|자랑)|기특[한해했]|"
        r"칭찬[을 ]?받|인정[을 ]?받"),
}
# 뺀 것: 「보고 싶어」 단독(해보고/먹어보고 싶어를 긁는다), 「든든한」(든든한 직장이 대부분),
# 「소중한」(소중한 시간·기회 등 대상이 사람이 아닌 경우가 많다).
# 재현율을 조금 잃더라도 **기계적으로 확실한 오탐**은 사람에게 보내지 않는다.

AXIS_KO = {"affection": "애정", "proud": "뿌듯함"}

# 실측으로 수확이 거의 없는 표현 — 기본으로 뺀다 (`--all` 로 되살린다).
# 2026-09-28 판정 75건 기준: 「사랑」 0/26 · 「좋아해」계열 1/11.
# AI Hub 가 상담 코퍼스라 '사랑'이 나오는 맥락이 죄다 "사랑하지만 부담돼" · "더는 나를
# 사랑하지 않는대" · "사랑받지 못해" 라 **단어만 있고 사용자의 애정 표현이 아니다.**
# 이 사실 자체가 V0 감정 어휘 사전의 결과다 — 「사랑」을 애정 신호로 넣으면 안 된다.
LOW_YIELD = {"사랑", "좋아해", "좋아했", "좋아하는 사람", "좋아하는 사이"}


def main():
    ap = argparse.ArgumentParser(description="애정·뿌듯함 후보 발화 수집")
    ap.add_argument("--dir", required=True, help="018.감성대화 폴더")
    ap.add_argument("--out", required=True, help="후보를 쓸 JSONL")
    ap.add_argument("--split", choices=["training", "validation", "both"], default="both")
    ap.add_argument("--max-per-axis", type=int, default=0, help="축당 상한 (0=제한 없음)")
    ap.add_argument("--all", action="store_true",
                    help="수확이 거의 없는 표현(LOW_YIELD)까지 전부 담는다")
    args = ap.parse_args()

    here = os.path.dirname(os.path.abspath(__file__))
    spec = importlib.util.spec_from_file_location("cv", os.path.join(here, "convert.py"))
    cv = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(cv)

    splits = ["training", "validation"] if args.split == "both" else [args.split]
    rows = []
    for s in splits:
        rows += [{**r, "split": s} for r in cv.read_split(args.dir, s)]

    seen = set()
    out, per_axis, dupes, low = [], Counter(), 0, Counter()
    for r in rows:
        user_turns = [(k, v) for k, v in r["turns"] if k.startswith("HS")]
        for k, text in user_turns:
            for axis, rx in PATTERNS.items():
                m = rx.search(text)
                if not m:
                    continue
                norm = re.sub(r"\s+", " ", text).strip()
                if norm in seen:          # 같은 문장이 여러 번 나온다 — 한 번만 본다
                    dupes += 1
                    continue
                seen.add(norm)
                if not args.all and m.group(0) in LOW_YIELD:
                    low[m.group(0)] += 1
                    break
                out.append({
                    # ⚠ split 을 붙여야 유일하다 — AI Hub talk-id 는 training 과 validation
                    # 사이에서 겹친다(실측 9건, 예: Pro_05153_00002 가 양쪽에 다른 내용으로 있다).
                    # 겹치면 판정 화면에서 두 항목이 한 판정을 공유해 하나가 검토되지 않는다.
                    "id": f"{r['split']}_{r['id']}_{k}_{axis}",
                    "sourceId": r["id"],
                    "axis": axis,
                    "match": m.group(0),
                    "sentence": norm,
                    "context": [{"speaker": "user" if kk.startswith("HS") else "assistant",
                                 "text": vv, "focus": kk == k}
                                for kk, vv in r["turns"]],
                    "aihub": {"major": r["major"], "minor": r["minor"],
                              "age": r["age"], "topic": r["topic"]},
                    "split": r["split"],
                })
                per_axis[axis] += 1
                break                     # 한 발화는 한 축에만 — 사람이 둘 다 고를 수 있다

    if args.max_per_axis:
        import random
        random.Random(0).shuffle(out)
        keep, cap = [], Counter()
        for c in out:
            if cap[c["axis"]] < args.max_per_axis:
                keep.append(c)
                cap[c["axis"]] += 1
        out = keep
        per_axis = cap

    with open(args.out, "w", encoding="utf-8") as f:
        for c in out:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")

    print(f"대화 {len(rows):,}건 · 후보 {len(out):,}건 → {args.out}")
    if low:
        n = sum(low.values())
        print(f"  수확 낮은 표현 {n:,}건 제외: " +
              ", ".join(f"{k} {v:,}" for k, v in low.most_common()))
        print("  (전부 보려면 --all)")
    for a, n in per_axis.most_common():
        print(f"  {AXIS_KO[a]:<5} {n:,}건")
    print(f"  (같은 문장 {dupes:,}건은 한 번만 담았습니다)")
    print("\n다음: eval/aihub/harvest.html 을 열어 이 파일을 불러오세요.")
    print("⚠ 이건 v2 골드가 아닙니다 — 어휘 사전 재료이자 자체 생성 세트의 씨앗입니다.")


if __name__ == "__main__":
    main()
