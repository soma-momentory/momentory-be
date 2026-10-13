#!/usr/bin/env python3
"""
감정 어휘 사전 점검 — 예문 시험 · AI Hub 커버리지 · 놓친 단어 후보.

    python3 check_lexicon.py                       # 예문 시험만
    python3 check_lexicon.py --aihub "…/018.감성대화"   # + AI Hub 커버리지와 놓친 단어 후보

사전 자체는 emotion_lexicon.json. 이 스크립트는 그걸 어떻게 쓰는지의 기준 구현이기도 하다 —
classify() 가 '명시 / 평가어만 / 약한 신호만 / 신호 없음' 을 가른다.
"""

import argparse
import importlib.util
import json
import os
import re
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))


def load(path=os.path.join(HERE, "emotion_lexicon.json")):
    lex = json.load(open(path, encoding="utf-8"))
    explicit = []                                   # (compiled, 감정키 또는 라벨, 층위)
    for key, v in lex["emotions"].items():
        for tier in ("name", "colloquial"):
            for e in v[tier]:
                explicit.append((re.compile(e["p"]), key, tier))
    for e in lex["ambiguous"]:
        explicit.append((re.compile(e["p"]), "/".join(e["candidates"]), "ambiguous"))
    for e in lex["out_of_scope"]:
        explicit.append((re.compile(e["p"]), "체계밖:" + e["label"], "out_of_scope"))
    evaluative = [(re.compile(e["p"]), e["kind"]) for e in lex["evaluative"]]
    weak = [re.compile(e["p"]) for e in lex["weak"]]
    return lex, explicit, evaluative, weak


def classify(text, explicit, evaluative, weak):
    """명시 여부와 근거를 돌려준다. 부정·타인·인용 규칙은 여기 없다(정본 §5.1 — 감지기 단계)."""
    hits = [(m.group(0), k, t) for rx, k, t in explicit for m in [rx.search(text)] if m]
    if hits:
        return "명시", hits
    ev = [(m.group(0), kind) for rx, kind in evaluative for m in [rx.search(text)] if m]
    if ev:
        return "평가어만", ev
    if any(rx.search(text) for rx in weak):
        return "약한 신호만", []
    return "신호 없음", []


CASES = [
    # (문장, 정본상 명시인가, 출처)
    ("내 의견이 빠져서 서운했어.", True, "정본 §6"),
    ("결과가 걱정됐어.", True, "정본 §6"),
    ("처음엔 화났는데 나중엔 서운했어.", True, "정본 §6"),
    ("내 의견만 빠졌어.", False, "정본 §6"),
    ("친구가 서운했다고 했어.", False, "정본 §6 · 타인"),
    ("'서운하다'는 말을 들었어.", False, "정본 §6 · 인용"),
    ("화난 건 아니었어.", False, "정본 §6 · 부정"),
    ("열심히 준비했는데 내 기여만 빠졌어. 인정받지 못한 것 같아.", False, "정본 SFT 예시 1"),
    ("합격 명단을 몇 번이나 다시 봤어. 내가 해냈다는 게 믿기지 않아.", False, "정본 SFT 예시 2"),
    ("친구는 별일 아니라는데, 나는 그 말 때문에 너무 마음이 상해서 하던 일에 집중할 수 없었어.", True, "정본 SFT 예시 3"),
    ("오늘 친구가 약속을 취소했어.", False, "정본 SFT 예시 4"),
    ("개빡침", True, "구어"), ("킹받네 진짜", True, "구어"), ("완전 멘붕 왔어", True, "구어"),
    ("현타 와", True, "구어"), ("쪽팔려 죽겠어", True, "구어"), ("엄마한테 미안했어", True, "미안함"), ("야근 때문에 너무 지쳤어", True, "지침"), ("흥분해서 앞을 못 봤어", False, "오탐"),
    ("오늘 기분 째진다", True, "구어"), ("이제 좀 살 것 같아", True, "구어"),
    ("사랑니 뽑았어", False, "오탐"), ("설레발 치지 마", False, "오탐"),
    ("해외여행을 가보고 싶어", False, "오탐"), ("너무 힘들어", False, "평가어"),
    ("감사 보고서 쓰느라 바빴어", False, "오탐"), ("화요일에 회의가 있었어", False, "오탐"),
    ("분명히 말했는데", False, "오탐"), ("교통이 편해서 좋아", False, "오탐"),
]
KNOWN = {"정본 §6 · 타인", "정본 §6 · 인용", "정본 §6 · 부정"}   # 사전만으로는 못 가름 — 규칙 몫


def run_cases(explicit, evaluative, weak):
    ok = fail = known = 0
    print("── 예문 시험 ──")
    for text, want, src in CASES:
        got, hits = classify(text, explicit, evaluative, weak)
        is_exp = got == "명시"
        if is_exp == want:
            ok += 1; mark = "✓"
        elif src in KNOWN:
            known += 1; mark = "△"
        else:
            fail += 1; mark = "✗"
        why = ", ".join(f"{h[0]}→{h[1]}" for h in hits[:2]) if hits else ""
        print(f"  {mark} [{src:<14}] {text[:34]:<36} → {got:<6} {why}")
    print(f"\n  통과 {ok} · 실패 {fail} · 규칙 몫 {known}(부정·타인·인용 — 사전만으로는 못 가른다)")
    return fail


def run_aihub(root, explicit, evaluative, weak):
    spec = importlib.util.spec_from_file_location("cv", os.path.join(HERE, "..", "aihub", "convert.py"))
    cv = importlib.util.module_from_spec(spec); spec.loader.exec_module(cv)
    rows = []
    for s in ("training", "validation"):
        rows += cv.read_split(root, s)
    dist = Counter(); pool = []
    for r in rows:
        text = " ".join(v for k, v in r["turns"] if k.startswith("HS"))
        got, _ = classify(text, explicit, evaluative, weak)
        dist[got] += 1
        if got != "명시":
            pool.append(text)
    n = len(rows)
    print(f"\n── AI Hub {n:,}건 (사용자 발화 기준) ──")
    for k in ("명시", "평가어만", "약한 신호만", "신호 없음"):
        print(f"  {k:<7} {dist[k]:>6,}  ({dist[k]/n:.1%})")
    print(f"  → 간접 후보(명시 아님) {len(pool):,}건")

    # 놓친 단어 후보 — 간접 후보 안에서 '-스럽다'와 '-감' 명사를 센다
    sur, gam = Counter(), Counter()
    for t in pool:
        for w in re.findall(r"([가-힣]{1,4})스(?:러|럽)", t):
            sur[w + "스럽다"] += 1
        for w in re.findall(r"(?<![가-힣])([가-힣]{1,3}감)(?=[이을은도에과]|\s|$)", t):
            gam[w] += 1
    print("\n── 놓친 단어 후보 (간접 후보 안에서 자주 나온 것 — 사람이 판단) ──")
    print("  -스럽다: " + ", ".join(f"{k} {v}" for k, v in sur.most_common(20)))
    print("  -감    : " + ", ".join(f"{k} {v}" for k, v in gam.most_common(20)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--aihub", help="018.감성대화 폴더 — 주면 커버리지와 놓친 단어 후보까지")
    args = ap.parse_args()
    _, explicit, evaluative, weak = load()
    fail = run_cases(explicit, evaluative, weak)
    if args.aihub:
        run_aihub(args.aihub, explicit, evaluative, weak)
    raise SystemExit(1 if fail else 0)


if __name__ == "__main__":
    main()
