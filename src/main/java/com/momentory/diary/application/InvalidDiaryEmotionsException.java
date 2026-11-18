package com.momentory.diary.application;

public class InvalidDiaryEmotionsException extends RuntimeException {
    public InvalidDiaryEmotionsException() { super("감정과 사건을 확인해주세요."); }
}
