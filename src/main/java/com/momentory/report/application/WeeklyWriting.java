package com.momentory.report.application;

/** 화면에 나갈 글 한 벌 — 저장된 것이든, 위기 주의 고정 문장이든, AI 실패 때의 대체 문장이든 같은 모양이다. */
record WeeklyWriting(String coverLine, String review, String goodWords, String okLine) {
}
