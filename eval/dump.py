#!/usr/bin/env python3
"""
세션 덤프 — DB 의 `state_json` 을 평가 파이프라인이 쓰는 두 형식으로 꺼낸다.

  1) 대화 (프로브 형식)  → 프롬프트 변형을 갈아가며 예측을 다시 뽑을 입력
  2) 예측 (채점기 형식)  → 앱이 운영 설정으로 이미 만든 결과

**대화와 예측을 나누는 이유**(계획 §5.1.1): 앱은 운영 temperature(0.7)로 돌지만 평가는 0 이어야
하고, 같은 대화에 여러 프롬프트 변형을 돌려 비교해야 한다. 그래서 보통은 (1) 만 쓰고 예측은
프로브 러너로 다시 뽑는다. (2) 는 "운영 설정이 실제로 뭘 냈나"를 볼 때만 쓴다.

DB 에 직접 붙지 않는다 — 내보내기는 psql 이 하고 이 스크립트는 변환만 한다:

    psql "$DB_URL" -At -c \\
      "SELECT state_json FROM retrospects WHERE status='COMPLETE' ORDER BY id" \\
      > sessions.jsonl

    python3 dump.py --in sessions.jsonl \\
      --conversations pilot-conversations.txt \\
      --predictions pilot-pred-prod.json
"""

import argparse
import json
import re
import sys

# RetrospectEngine#optionLines 가 "  1. 라벨" 형태로 붙인다. 프로브 형식은 줄 단위라 걷어낸다.
# PromptFactory 도 프롬프트를 만들 때 같은 줄을 빼므로, 이걸 지워도 재실행 프롬프트는 동일하다.
OPTION_LINE = re.compile(r"^ {2}\d+\. ")


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


def to_conversation(state):
    """프로브 형식 한 블록. 사용자 발화가 없으면 None(빈 세션은 버린다)."""
    lines = []
    users = 0
    for m in state.get("messages") or []:
        text = clean(m.get("content"))
        if not text:
            continue
        if m.get("role") == "user":
            users += 1
            lines.append("U:" + text)
        else:
            lines.append("A:" + text)
    if users == 0:
        return None
    schedule = (state.get("schedule") or "").strip() or "없음"
    return "#{}|{}\n{}".format(state.get("id"), schedule, "\n".join(lines))


def to_prediction(state):
    """
    채점기 형식. 스냅샷은 열거형을 <b>이름</b>으로 담는다(ANGRY/DURING)—
    채점기는 키(angry/during)를 쓰므로 소문자로 낮춘다. 10종 모두 key == name.toLowerCase() 다.
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

    return {"sessionId": state.get("id"), "events": events, "emotions": emotions}


def main():
    ap = argparse.ArgumentParser(description="state_json → 대화/예측")
    ap.add_argument("--in", dest="src", required=True, help="state_json 묶음 (JSONL 또는 JSON 배열, '-'=stdin)")
    ap.add_argument("--conversations", help="프로브 형식 대화를 쓸 파일")
    ap.add_argument("--predictions", help="채점기 형식 예측을 쓸 파일 (운영 설정 결과)")
    args = ap.parse_args()
    if not args.conversations and not args.predictions:
        sys.exit("--conversations 나 --predictions 중 하나는 지정해야 합니다.")

    states = load(args.src)
    if not states:
        sys.exit("입력이 비었습니다.")

    if args.conversations:
        blocks = [b for b in (to_conversation(s) for s in states) if b]
        with open(args.conversations, "w", encoding="utf-8") as f:
            f.write("\n\n".join(blocks) + "\n")
        skipped = len(states) - len(blocks)
        print("대화 {}세션 → {}{}".format(
            len(blocks), args.conversations,
            "  (사용자 발화 없는 {}개 제외)".format(skipped) if skipped else ""))

    if args.predictions:
        preds = [to_prediction(s) for s in states]
        with open(args.predictions, "w", encoding="utf-8") as f:
            json.dump(preds, f, ensure_ascii=False, indent=1)
        empty = sum(1 for p in preds if not p["emotions"])
        print("예측 {}세션 → {}{}".format(
            len(preds), args.predictions,
            "  ⚠ 감정 0개인 세션 {}개 — 조용한 호출 실패일 수 있다".format(empty) if empty else ""))


if __name__ == "__main__":
    main()
