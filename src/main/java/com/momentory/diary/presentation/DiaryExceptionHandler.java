package com.momentory.diary.presentation;

import java.time.DateTimeException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.diary.application.DiaryNotFoundException;

@RestControllerAdvice(assignableTypes = DiaryController.class)
public class DiaryExceptionHandler {

    @ExceptionHandler(com.momentory.retrospect.application.AlreadyRetrospectedTodayException.class)
    ResponseEntity<ApiErrorResponse> handleAlreadyWritten() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse("ALREADY_RETROSPECTED_TODAY",
                        "오늘은 이미 회고를 완료했어요. 회고는 하루에 한 번만 할 수 있어요."));
    }

    @ExceptionHandler(DiaryNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleDiaryNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse("DIARY_NOT_FOUND", "일기를 찾을 수 없습니다."));
    }

    @ExceptionHandler(com.momentory.diary.application.InvalidDiaryEmotionsException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidEmotions() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("INVALID_REQUEST", "감정과 사건을 확인해주세요."));
    }

    /** 월이 1~12 를 벗어나는 등 연·월 조합이 잘못됐을 때({@code YearMonth.of} 가 던진다). */
    @ExceptionHandler(DateTimeException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidYearMonth() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("INVALID_REQUEST", "잘못된 연·월입니다."));
    }
}
