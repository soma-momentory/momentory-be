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

    @ExceptionHandler(DiaryNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleDiaryNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse("DIARY_NOT_FOUND", "일기를 찾을 수 없습니다."));
    }

    @ExceptionHandler(com.momentory.diary.application.DiaryWeatherDateException.class)
    ResponseEntity<ApiErrorResponse> handleWeatherDate() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse("DIARY_WEATHER_DATE_MISMATCH", "지난 일기에는 현재 날씨를 기록할 수 없습니다."));
    }

    @ExceptionHandler(com.momentory.diary.application.WeatherUnavailableException.class)
    ResponseEntity<ApiErrorResponse> handleWeatherUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiErrorResponse("WEATHER_UNAVAILABLE", "날씨를 가져오지 못했어요. 일기는 그대로 저장돼요."));
    }

    /** 월이 1~12 를 벗어나는 등 연·월 조합이 잘못됐을 때({@code YearMonth.of} 가 던진다). */
    @ExceptionHandler(DateTimeException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidYearMonth() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("INVALID_REQUEST", "잘못된 연·월입니다."));
    }
}
