#!/usr/bin/env python3
"""
2인 독립 라벨 병합 — 불일치를 유형별로 뽑고 일치도(IAA)를 잰다.

계획 §8.5 의 조정 절차를 실행 가능하게 만든 자리다. 두 사람은 서로의 결과를 보기 전에
독립적으로 라벨링하고, 이 스크립트가 **무엇이 얼마나 갈렸는지**를 낸다.

    python3 merge.py --a data/gold-A.json --b data/gold-B.json \\
      --report data/disagreements.md --merge data/gold-agreed.json

내는 것:

  1) **일치도(IAA)** — 계획 §8.9 의 상한선 좌표.
     · 감정: 대표 감정 11종을 라벨별 이진 카파로 재고 매크로 평균한다(다중 라벨이라 단일 카파 불가)
     · 강도: 1·2·3 가중 카파(quadratic). '판단 어려움'은 등급이 아니라 **보류**라 따로 센다
     · 명시적 표현·사건 개수: 단순 카파와 일치율
  2) **불일치 목록** — 원인을 사건 구분 / 대표 감정 / 강도 / 명시적 표현 중 무엇인지로 분류한다.
     조정 회의에서 이 순서로 훑는다.
  3) **합의 골드**(`--merge`) — 두 라벨이 완전히 같은 세션만 담는다. 갈린 세션은 사람이 조정한
     뒤에 넣어야 하므로 **자동으로 한쪽을 고르지 않는다.**

감정 일치는 **세션 단위**로 잰다(계획 §9.2) — 사건 정렬 규칙의 자의성이 IAA 에 섞이면
"기준이 안 맞는 건지 정렬이 안 맞는 건지"를 구분할 수 없다. 사건·명시적 표현처럼 사건에
매인 항목만 근거 발화 IoU 로 짝지어 비교한다.
"""

import argparse
import json
import sys
from collections import Counter, defaultdict

EMOTIONS = ["joy", "calm", "proud", "affection", "sad",
            "anxious", "angry", "disappointed", "ashamed", "confused", "sorry"]
KO = {"joy": "기쁨", "calm": "평온", "proud": "뿌듯함", "affection": "애정", "sad": "슬픔",
      "anxious": "불안", "angry": "분노", "disappointed": "실망", "ashamed": "부끄러움",
      "confused": "혼란", "sorry": "미안함"}
EXPLICIT_KO = {"yes": "있음", "no": "없음", "unsure": "판단 어려움", None: "미입력"}
UND_KO = {"no_emotion_info": "감정 정보 부족", "emotion_unclear": "대표 감정 판단 어려움",
          "out_of_scope": "분류 체계 밖", None: "—"}
IOU_THRESHOLD = 0.5


# ── 통계 ─────────────────────────────────────────────────────────────────
def cohen_kappa(pairs):
    """pairs: [(a, b)]. 완전 일치면 1.0, 우연 수준이면 0."""
    n = len(pairs)
    if not n:
        return None
    po = sum(1 for a, b in pairs if a == b) / n
    ca, cb = Counter(a for a, _ in pairs), Counter(b for _, b in pairs)
    pe = sum(ca[k] * cb[k] for k in set(ca) | set(cb)) / (n * n)
    if pe == 1:
        return 1.0 if po == 1 else 0.0
    return (po - pe) / (1 - pe)


def quadratic_weighted_kappa(pairs, lo=1, hi=3):
    """순서형 등급용. 1↔3 처럼 멀리 틀린 것에 더 큰 벌점."""
    n = len(pairs)
    if not n:
        return None
    k = hi - lo + 1
    rng = float((k - 1) ** 2) or 1.0
    obs = [[0] * k for _ in range(k)]
    for a, b in pairs:
        obs[a - lo][b - lo] += 1
    ha, hb = Counter(a for a, _ in pairs), Counter(b for _, b in pairs)
    num = den = 0.0
    for i in range(k):
        for j in range(k):
            w = ((i - j) ** 2) / rng
            num += w * obs[i][j]
            den += w * ha[i + lo] * hb[j + lo] / n
    return 1 - num / den if den else None


def iou(a, b):
    sa, sb = set(a), set(b)
    if not sa and not sb:
        return 1.0
    union = sa | sb
    return len(sa & sb) / len(union) if union else 0.0


def align_events(ea, eb):
    """근거 발화 IoU 로 사건을 짝짓는다(계획 §9.1). 사건이 최대 2개라 전수 탐색."""
    if not ea or not eb:
        return [], list(ea), list(eb)
    best, pairs = -1.0, []
    idx_b = list(range(len(eb)))
    orders = [idx_b] if len(idx_b) < 2 else [idx_b, idx_b[::-1]]
    for order in orders:
        cand, total = [], 0.0
        used = set()
        for i, x in enumerate(ea):
            for j in order:
                if j in used:
                    continue
                v = iou(x.get("evidence") or [], eb[j].get("evidence") or [])
                if v >= IOU_THRESHOLD:
                    cand.append((i, j, v))
                    total += v
                    used.add(j)
                    break
        if total > best:
            best, pairs = total, cand
    ai = {i for i, _, _ in pairs}
    bi = {j for _, j, _ in pairs}
    return ([(ea[i], eb[j]) for i, j, _ in pairs],
            [e for i, e in enumerate(ea) if i not in ai],
            [e for j, e in enumerate(eb) if j not in bi])


# ── 추출 ─────────────────────────────────────────────────────────────────
def by_id(rows, who):
    out = {}
    for r in rows:
        sid = r.get("sessionId")
        if sid in out:
            print("⚠ {}: 세션 {} 가 중복입니다. 뒤엣것을 씁니다.".format(who, sid), file=sys.stderr)
        out[sid] = r
    return out


def scorable(row):
    """채점 제외 사건(대표 감정 판단 어려움 / 분류 체계 밖)에 매인 감정은 IAA 에서 뺀다."""
    blocked = {e["id"] for e in row.get("events") or []
               if e.get("undecidable") in ("emotion_unclear", "out_of_scope")}
    return [m for m in row.get("emotions") or []
            if m.get("eventId") not in blocked and m.get("experiencer") != "other"]


def emotion_set(row):
    return {m.get("normalized") for m in scorable(row)}


def strongest(row):
    """(세션, 감정) 하나에 값 하나 — 같은 감정이 여러 번이면 가장 강한 것으로 접는다."""
    out = {}
    for m in scorable(row):
        k, cur = m.get("normalized"), out.get(m.get("normalized"))
        v = m.get("intensity")
        if cur is None or (v is not None and (cur.get("intensity") is None or v > cur["intensity"])):
            out[k] = m
    return out


# ── 본체 ─────────────────────────────────────────────────────────────────
def main():
    ap = argparse.ArgumentParser(description="2인 독립 라벨 병합·불일치 리포트")
    ap.add_argument("--a", required=True, help="라벨러 A 의 gold JSON")
    ap.add_argument("--b", required=True, help="라벨러 B 의 gold JSON")
    ap.add_argument("--report", help="불일치 리포트를 쓸 Markdown 파일")
    ap.add_argument("--merge", help="두 사람이 완전히 일치한 세션만 담을 골드 파일")
    args = ap.parse_args()

    A = by_id(json.load(open(args.a, encoding="utf-8")), "A")
    B = by_id(json.load(open(args.b, encoding="utf-8")), "B")

    for sid, r in list(A.items()) + list(B.items()):
        if r.get("taxonomy") != "v2":
            print("⚠ 세션 {} 의 taxonomy 가 v2 가 아닙니다 ({}). 정본 택소노미인지 확인하세요."
                  .format(sid, r.get("taxonomy")), file=sys.stderr)
            break

    shared = sorted(set(A) & set(B))
    only_a, only_b = sorted(set(A) - set(B)), sorted(set(B) - set(A))
    if not shared:
        sys.exit("두 파일에 공통 세션이 없습니다. 같은 대화 파일로 라벨링했는지 확인하세요.")

    emo_pairs = {e: [] for e in EMOTIONS}   # 라벨별 이진 카파용
    int_pairs, int_hold = [], []            # 강도 등급 / 보류 일치
    expl_pairs, count_pairs = [], []
    rows, agreed = [], []

    for sid in shared:
        a, b = A[sid], B[sid]
        sa, sb = emotion_set(a), emotion_set(b)
        for e in EMOTIONS:
            emo_pairs[e].append((e in sa, e in sb))

        issues = []
        if sa != sb:
            issues.append(("대표 감정",
                           "A={} / B={}  (A만 {} · B만 {})".format(
                               fmt(sa), fmt(sb), fmt(sa - sb) or "—", fmt(sb - sa) or "—")))

        ma, mb = strongest(a), strongest(b)
        for e in sorted(sa & sb):
            ia, ib = ma[e].get("intensity"), mb[e].get("intensity")
            int_hold.append((ia is None, ib is None))
            if ia is not None and ib is not None:
                int_pairs.append((ia, ib))
                if ia != ib:
                    issues.append(("강도", "{}: A={} / B={}{}".format(
                        KO[e], ia, ib, "  ⚠ 2단계" if abs(ia - ib) >= 2 else "")))
            elif ia != ib:
                issues.append(("강도", "{}: A={} / B={}  (보류 여부가 갈림)".format(
                    KO[e], ia if ia is not None else "판단 어려움",
                    ib if ib is not None else "판단 어려움")))

        ea, eb = a.get("events") or [], b.get("events") or []
        count_pairs.append((len(ea), len(eb)))
        matched, extra_a, extra_b = align_events(ea, eb)
        if len(ea) != len(eb) or extra_a or extra_b:
            issues.append(("사건 구분", "A {}개 / B {}개, 짝 못 지은 사건 A {} · B {}".format(
                len(ea), len(eb), len(extra_a), len(extra_b))))
        for x, y in matched:
            expl_pairs.append((x.get("explicit"), y.get("explicit")))
            if x.get("explicit") != y.get("explicit"):
                issues.append(("명시적 표현", "사건 {}/{}: A={} / B={}".format(
                    x.get("id"), y.get("id"),
                    EXPLICIT_KO.get(x.get("explicit")), EXPLICIT_KO.get(y.get("explicit")))))
            if x.get("undecidable") != y.get("undecidable"):
                issues.append(("판단 불가", "사건 {}/{}: A={} / B={}".format(
                    x.get("id"), y.get("id"),
                    UND_KO.get(x.get("undecidable")), UND_KO.get(y.get("undecidable")))))

        if issues:
            rows.append((sid, issues, a, b))
        else:
            merged = dict(a)
            merged["labeler"] = "A+B"
            agreed.append(merged)

    # ── 출력 ──
    print("공통 세션 {}건 · 완전 일치 {}건 ({:.0%})".format(
        len(shared), len(agreed), len(agreed) / len(shared)))
    if only_a or only_b:
        print("⚠ A 에만 {}건, B 에만 {}건 — 같은 세트를 라벨링했는지 확인하세요."
              .format(len(only_a), len(only_b)))

    print("\n── 일치도(IAA) ──")
    per = {e: cohen_kappa(p) for e, p in emo_pairs.items()}
    seen = [e for e in EMOTIONS if any(x or y for x, y in emo_pairs[e])]
    macro = [per[e] for e in seen if per[e] is not None]
    print("  감정 Macro 카파      {}  (등장한 감정 {}종)".format(
        num(sum(macro) / len(macro) if macro else None), len(seen)))
    print("  강도 가중 카파       {}  (비교 쌍 {})".format(
        num(quadratic_weighted_kappa(int_pairs)), len(int_pairs)))
    print("  강도 보류 일치       {}  (판단 어려움을 같이 썼는가, {}쌍)".format(
        num(cohen_kappa(int_hold)), len(int_hold)))
    print("  명시적 표현 카파     {}  (짝지은 사건 {})".format(
        num(cohen_kappa(expl_pairs)), len(expl_pairs)))
    print("  사건 개수 일치율     {}".format(
        num(sum(1 for x, y in count_pairs if x == y) / len(count_pairs) if count_pairs else None)))

    print("\n  감정별 카파 (낮은 순 — 기준 문서를 보강할 후보):")
    for e in sorted(seen, key=lambda x: (per[x] is None, per[x])):
        sup = sum(1 for x, y in emo_pairs[e] if x or y)
        print("    {:<5} {}  (등장 {}건)".format(KO[e], num(per[e]), sup))

    kinds = Counter(k for _, issues, _, _ in rows for k, _ in issues)
    if kinds:
        print("\n── 불일치 원인 ──")
        for k, n in kinds.most_common():
            print("  {:<10} {}건".format(k, n))

    if args.report:
        write_report(args.report, rows, shared, agreed, only_a, only_b)
        print("\n불일치 리포트 {}건 → {}".format(len(rows), args.report))
    if args.merge:
        with open(args.merge, "w", encoding="utf-8") as f:
            json.dump(agreed, f, ensure_ascii=False, indent=1)
        print("합의 골드 {}건 → {}  (갈린 {}건은 조정 뒤 직접 넣으세요)"
              .format(len(agreed), args.merge, len(rows)))


def fmt(s):
    return ", ".join(KO.get(x, str(x)) for x in sorted(s, key=lambda k: EMOTIONS.index(k)
                                                       if k in EMOTIONS else 99))


def num(v):
    return "—" if v is None else "{:.3f}".format(v)


def write_report(path, rows, shared, agreed, only_a, only_b):
    with open(path, "w", encoding="utf-8") as f:
        f.write("# 라벨 불일치 조정 목록\n\n")
        f.write("공통 {}건 · 완전 일치 {}건 · 조정 필요 **{}건**\n\n".format(
            len(shared), len(agreed), len(rows)))
        if only_a or only_b:
            f.write("> ⚠ A 에만 {}건 / B 에만 {}건 있습니다.\n\n".format(len(only_a), len(only_b)))
        f.write("계획 §8.5 순서로 훑습니다 — ①근거 발화를 나란히 확인 ②원인 유형 기록 "
                "③현재 기준으로 되면 합의 ④반복되면 Notion 기준 문서에 예시 추가 "
                "⑤발화만으로 안 되면 합의 어려움으로 남김\n\n")
        for sid, issues, a, b in rows:
            f.write("## {}\n\n".format(sid))
            for e in a.get("events") or []:
                f.write("- A 사건 {}: {} · 발화 {}\n".format(
                    e.get("id"), e.get("summary") or e.get("label") or "—",
                    " ".join("U%d" % n for n in e.get("evidence") or []) or "없음"))
            for e in b.get("events") or []:
                f.write("- B 사건 {}: {} · 발화 {}\n".format(
                    e.get("id"), e.get("summary") or e.get("label") or "—",
                    " ".join("U%d" % n for n in e.get("evidence") or []) or "없음"))
            f.write("\n")
            for kind, detail in issues:
                f.write("- **{}** — {}\n".format(kind, detail))
            for who, r in (("A", a), ("B", b)):
                if r.get("note"):
                    f.write("- {} 메모: {}\n".format(who, r["note"]))
            f.write("\n- [ ] 합의됨  · 결론: \n- [ ] 합의 어려움\n\n")


if __name__ == "__main__":
    main()
