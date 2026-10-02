#!/usr/bin/env python3
"""
세션 덤프 — DB 의 `state_json` 을 평가 파이프라인이 쓰는 형식으로 꺼내고 **비식별화**한다.

  1) 대화 (프로브 형식)  → 라벨링 입력이자, 프롬프트 변형을 갈아가며 예측을 다시 뽑을 입력
  2) 예측 (채점기 형식)  → 앱이 운영 설정으로 이미 만든 결과

**대화와 예측을 나누는 이유**(계획 §7.2.1): 앱은 운영 temperature(0.7)로 돌지만 평가는 0 이어야
하고, 같은 대화에 여러 프롬프트 변형을 돌려 비교해야 한다. 그래서 보통은 (1) 만 쓰고 예측은
프로브 러너로 다시 뽑는다. (2) 는 "운영 설정이 실제로 뭘 냈나"를 볼 때만 쓴다.

⚠ **실사용자 데이터를 다룰 때**(계획 §7.1): 개인정보를 제거하고 동의 범위를 확인한 뒤에만 쓴다.
`--anonymize` 가 **확실한 패턴만** 자동으로 가린다(전화·이메일·주민번호·계좌·URL·아이디).
**사람 이름은 자동으로 못 가린다** — `--review-names` 로 후보를 뽑아 사람이 검토한 뒤
`--names` 로 넘겨야 한다. 자동 마스킹만 믿고 라벨러에게 넘기지 말 것.

⚠ **예측(2)은 구 택소노미다.** 운영 앱의 `Emotion` 10종은 정본 교체 전 값이라(계획 §0.1),
출력에 `"taxonomy": "v1"` 을 붙인다. 정본(v2) 골드와 **같은 채점표에 올리지 않는다.**
대화(1)는 감정 키를 담지 않으므로 택소노미와 무관하다 — **실사용 대화를 v2 로 라벨링해도 된다.**

DB 에 직접 붙지 않는다 — 내보내기는 psql 이 하고 이 스크립트는 변환만 한다:

    psql "$DB_URL" -At -c \\
      "SELECT state_json FROM retrospects WHERE status='COMPLETED' ORDER BY id" \\
      > sessions.jsonl

    # 100건 표본 + 비식별화 + 이름 후보 검토 파일
    python3 dump.py --in sessions.jsonl --anonymize --pseudonymize \\
      --sample 100 --seed 20260927 \\
      --review-names name-candidates.txt \\
      --id-map id-map.json \\
      --conversations data/conversations.txt

    # 사람이 name-candidates.txt 를 검토해 실제 이름만 남긴 뒤 다시 돌린다
    python3 dump.py --in sessions.jsonl --anonymize --pseudonymize \\
      --sample 100 --seed 20260927 --names names.txt \\
      --id-map id-map.json --conversations data/conversations.txt
"""

import argparse
import hashlib
import json
import random
import re
import sys
from collections import Counter

# RetrospectEngine#optionLines 가 "  1. 라벨" 형태로 붙인다. 프로브 형식은 줄 단위라 걷어낸다.
# PromptFactory 도 프롬프트를 만들 때 같은 줄을 빼므로, 이걸 지워도 재실행 프롬프트는 동일하다.
OPTION_LINE = re.compile(r"^ {2}\d+\. ")

# ── 비식별화 ──────────────────────────────────────────────────────────────
# 오탐이 적은 패턴만 자동으로 가린다. 앞선 항목이 먼저 먹으므로 순서가 중요하다
# (전화가 계좌보다 앞이어야 "010-1234-5678" 이 [계좌] 로 가려지지 않는다).
#
# ⚠ 한글은 파이썬 정규식에서 `\w` 라 `\b` 가 "5678로" 같은 자리에서 경계를 못 잡는다.
#   그래서 단어 경계 대신 **숫자가 아닌지**를 직접 본다.
_NOT_D_BEFORE = r"(?<![\d-])"
_NOT_D_AFTER = r"(?![\d-])"


def _digits(s):
    return sum(c.isdigit() for c in s)


def _acct(m):
    """날짜(2026-09-27)를 계좌로 오인하지 않도록 자릿수 합이 10 이상일 때만 가린다."""
    return "[계좌]" if _digits(m.group(0)) >= 10 else m.group(0)


PII = [
    ("[이메일]",   re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+")),
    ("[URL]",      re.compile(r"https?://\S+|\bwww\.\S+")),
    ("[주민번호]", re.compile(_NOT_D_BEFORE + r"\d{6}\s*[-–]\s*[1-4]\d{6}" + _NOT_D_AFTER)),
    ("[전화]",     re.compile(_NOT_D_BEFORE + r"(?:\+?82[-\s]?)?0?1[016-9][-\s]?\d{3,4}[-\s]?\d{4}"
                              + _NOT_D_AFTER)),
    ("[전화]",     re.compile(_NOT_D_BEFORE + r"0\d{1,2}[-\s]\d{3,4}[-\s]\d{4}" + _NOT_D_AFTER)),
    ("[카드]",     re.compile(_NOT_D_BEFORE + r"(?:\d{4}[-\s]){3}\d{4}" + _NOT_D_AFTER)),
    (_acct,        re.compile(_NOT_D_BEFORE + r"\d{2,6}[-\s]\d{2,6}[-\s]\d{2,7}" + _NOT_D_AFTER)),
    ("[아이디]",   re.compile(r"(?<![\w가-힣])@[A-Za-z0-9._]{3,}")),
]

# 사람 이름 "후보" — 호칭·조사가 붙은 2~4자 한글. 오탐이 많아 자동으로 가리지 않고 검토용으로만 센다.
# `{2,4}?` 가 비탐욕이라 "지연님한테" 에서 호칭이 딸려 온 "지연님" 이 아니라 "지연" 을 잡는다.
NAME_HINT = re.compile(
    r"(?<![가-힣])([가-힣]{2,4}?)"
    r"(?:님|씨|선배|후배|쌤|선생님|팀장|과장|부장|대리|이가|이는|이랑|이한테|한테|에게)"
)
# 후보에서 뺄 일반명사 — 호칭이 붙어도 이름이 아닌 것들.
NAME_STOP = {
    "우리", "저희", "엄마", "아빠", "부모", "동생", "친구", "회사", "팀장", "과장", "부장", "대리",
    "사장", "고객", "손님", "그분", "이분", "저분", "본인", "자기", "자신", "사람", "학생", "교수",
    "의사", "간호", "기사", "점원", "직원", "상사", "동료", "선배", "후배", "언니", "오빠", "누나",
    "가족", "남편", "아내", "누구", "모두", "다들", "여기", "거기", "저기", "지금", "오늘", "내일",
}


def strip_pii(text):
    """태그는 문자열이거나, 매치를 받아 대체문을 돌려주는 함수다."""
    for tag, rx in PII:
        text = rx.sub(tag, text)
    return text


def apply_names(text, names, table):
    """검토를 마친 이름 목록을 `[사람N]` 으로 치환한다. 번호는 덤프 전체에서 일관된다."""
    for n in names:
        if n and n in text:
            if n not in table:
                table[n] = "[사람{}]".format(len(table) + 1)
            text = text.replace(n, table[n])
    return text


def name_candidates(states):
    counter = Counter()
    for s in states:
        for m in s.get("messages") or []:
            if m.get("role") != "user":
                continue
            for hit in NAME_HINT.findall(m.get("content") or ""):
                if hit not in NAME_STOP:
                    counter[hit] += 1
    return counter


# ── 변환 ─────────────────────────────────────────────────────────────────
def clean(text):
    """선택지 줄을 빼고 줄바꿈을 공백으로 접는다 — 프로브 형식이 한 줄 = 한 발화라서."""
    if not text:
        return ""
    kept = [ln for ln in text.splitlines() if not OPTION_LINE.match(ln)]
    return " ".join(" ".join(kept).split())


def load(path):
    """JSONL(psql -At) 또는 JSON 배열 둘 다 받는다."""
    raw = sys.stdin.read() if path == "-" else open(path, encoding="utf-8").read()
    raw = raw.strip()
    if not raw:
        return []
    if raw.startswith("["):
        return json.loads(raw)
    return [json.loads(line) for line in raw.splitlines() if line.strip()]


def session_id(state, pseudonymize):
    raw = str(state.get("id"))
    if not pseudonymize:
        return raw
    return "p-" + hashlib.sha256(raw.encode("utf-8")).hexdigest()[:8]


def to_conversation(state, sid, source, anonymize, names, table):
    """프로브 형식 한 블록. 사용자 발화가 없으면 None(빈 세션은 버린다)."""
    lines = []
    users = 0
    for m in state.get("messages") or []:
        text = clean(m.get("content"))
        if not text:
            continue
        if anonymize:
            text = strip_pii(text)
            if names:
                text = apply_names(text, names, table)
        if m.get("role") == "user":
            users += 1
            lines.append("U:" + text)
        else:
            lines.append("A:" + text)
    if users == 0:
        return None
    schedule = clean(state.get("schedule")) or "없음"
    if anonymize:
        schedule = strip_pii(schedule)
        if names:
            schedule = apply_names(schedule, names, table)
    return "#{}|{}|{}\n{}".format(sid, schedule, source, "\n".join(lines))


def to_prediction(state, sid):
    """
    채점기 형식. 스냅샷은 열거형을 <b>이름</b>으로 담는다(ANGRY/DURING)—
    채점기는 키(angry/during)를 쓰므로 소문자로 낮춘다.

    ⚠ 운영 앱은 아직 구 택소노미라 `taxonomy: "v1"` 을 붙인다. 정본(v2) 골드와 섞지 말 것.
    """
    def lower(v):
        return v.lower() if isinstance(v, str) else None

    events = [{
        "id": e.get("id"),
        "label": e.get("label"),
        "summary": e.get("summary"),
        "evidence": e.get("evidence") or [],
    } for e in (state.get("events") or [])]

    emotions = [{
        "eventId": e.get("eventId"),
        "normalized": lower(e.get("normalized")),
        "intensity": e.get("intensity"),
        "phase": lower(e.get("phase")),
        "evidenceIds": e.get("evidenceIds") or [],
    } for e in (state.get("emotions") or [])]

    return {"sessionId": sid, "taxonomy": "v1", "events": events, "emotions": emotions}


def main():
    ap = argparse.ArgumentParser(description="state_json → 대화/예측 (+비식별화)")
    ap.add_argument("--in", dest="src", required=True,
                    help="state_json 묶음 (JSONL 또는 JSON 배열, '-'=stdin)")
    ap.add_argument("--conversations", help="프로브 형식 대화를 쓸 파일")
    ap.add_argument("--predictions", help="채점기 형식 예측을 쓸 파일 (운영 설정 결과, 구 택소노미)")
    ap.add_argument("--anonymize", action="store_true",
                    help="확실한 PII 패턴을 가린다. 실사용자 데이터에는 반드시 켠다")
    ap.add_argument("--names", help="검토를 마친 사람 이름 목록 (한 줄에 하나). --anonymize 와 함께")
    ap.add_argument("--review-names", help="이름 후보와 빈도를 쓸 파일 — 사람이 검토할 입력")
    ap.add_argument("--pseudonymize", action="store_true",
                    help="세션 ID 를 해시로 바꾼다. 라벨러가 원본 세션을 되짚지 못하게")
    ap.add_argument("--id-map", help="가명 → 원본 ID 매핑을 쓸 파일. 라벨 세트와 분리 보관할 것")
    ap.add_argument("--sample", type=int, help="무작위로 N 세션만 고른다")
    ap.add_argument("--seed", type=int, default=0, help="--sample 의 시드 (기본 0, 재현용)")
    ap.add_argument("--source", choices=["real", "synthetic"], default="real",
                    help="데이터 출처. 대화 헤더에 실린다 (기본 real)")
    args = ap.parse_args()

    if not args.conversations and not args.predictions and not args.review_names:
        sys.exit("--conversations / --predictions / --review-names 중 하나는 지정해야 합니다.")
    if args.names and not args.anonymize:
        sys.exit("--names 는 --anonymize 와 함께 써야 합니다.")

    states = load(args.src)
    if not states:
        sys.exit("입력이 비었습니다.")

    if args.sample and args.sample < len(states):
        random.Random(args.seed).shuffle(states)
        states = states[:args.sample]
        print("표본 {}세션 (seed={})".format(len(states), args.seed))

    if args.review_names:
        counter = name_candidates(states)
        with open(args.review_names, "w", encoding="utf-8") as f:
            f.write("# 사람 이름 후보 — 실제 이름만 남기고 나머지 줄은 지운 뒤 --names 로 넘기세요.\n")
            f.write("# 자동 판별이 아닙니다. 호칭이 붙은 2~4자 한글을 센 것뿐입니다.\n")
            for name, n in counter.most_common():
                f.write("{}\t# {}회\n".format(name, n))
        print("이름 후보 {}종 → {}  (사람이 검토해야 합니다)".format(len(counter), args.review_names))

    names = []
    if args.names:
        with open(args.names, encoding="utf-8") as f:
            names = [ln.split("#")[0].strip() for ln in f]
        names = sorted({n for n in names if n}, key=len, reverse=True)
        print("검토된 이름 {}종을 가립니다".format(len(names)))
    elif args.anonymize and args.conversations:
        print("⚠ --names 없이 익명화합니다 — 사람 이름은 남습니다. --review-names 를 먼저 돌리세요.")

    table = {}
    ids = [(s, session_id(s, args.pseudonymize)) for s in states]

    if args.id_map:
        with open(args.id_map, "w", encoding="utf-8") as f:
            json.dump({sid: s.get("id") for s, sid in ids}, f, ensure_ascii=False, indent=1)
        print("ID 매핑 → {}  ⚠ 라벨 세트와 같은 곳에 두지 마세요".format(args.id_map))

    if args.conversations:
        blocks = [b for b in (to_conversation(s, sid, args.source, args.anonymize, names, table)
                              for s, sid in ids) if b]
        with open(args.conversations, "w", encoding="utf-8") as f:
            f.write("\n\n".join(blocks) + "\n")
        skipped = len(ids) - len(blocks)
        print("대화 {}세션 → {}{}".format(
            len(blocks), args.conversations,
            "  (사용자 발화 없는 {}개 제외)".format(skipped) if skipped else ""))
        if args.anonymize:
            print("가린 이름 {}종. **라벨링 전에 사람이 한 번 훑어보세요** — 자동 비식별화는 완전하지 않습니다."
                  .format(len(table)))

    if args.predictions:
        preds = [to_prediction(s, sid) for s, sid in ids]
        with open(args.predictions, "w", encoding="utf-8") as f:
            json.dump(preds, f, ensure_ascii=False, indent=1)
        empty = sum(1 for p in preds if not p["emotions"])
        print("예측 {}세션 → {}  (taxonomy=v1, 구 택소노미){}".format(
            len(preds), args.predictions,
            "  ⚠ 감정 0개인 세션 {}개 — 조용한 호출 실패일 수 있다".format(empty) if empty else ""))


if __name__ == "__main__":
    main()
