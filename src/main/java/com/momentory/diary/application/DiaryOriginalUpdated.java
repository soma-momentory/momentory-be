package com.momentory.diary.application;

/** 사용자가 일기 본문을 직접 고쳤다 — 그 일기에서 나온 것(편지)을 다시 만들 때 쓴다. */
public record DiaryOriginalUpdated(Long diaryId) {
}
