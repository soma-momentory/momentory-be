package com.momentory.report.presentation;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.momentory.auth.security.Login;
import com.momentory.auth.security.LoginPrincipal;
import com.momentory.report.application.WeeklyReportV2Service;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 주간 리포트 v2 조회 API — 옛 앱이 쓰는 {@code /api/v1/reports/weekly} 는 그대로 둔다. */
@Tag(name = "Reports", description = "주간 리포트 API")
@RestController
@RequestMapping("/api/v2/reports")
public class WeeklyReportV2Controller {

    private final WeeklyReportV2Service service;

    public WeeklyReportV2Controller(WeeklyReportV2Service service) {
        this.service = service;
    }

    @Operation(summary = "주간 리포트 v2 조회",
            description = "그 주(일~토, KST)의 공책 한 권을 돌려준다. date 는 그 주에 속한 아무 날이다. 생략하면 일기가 "
                    + "남은 가장 최근의 끝난 주를 연다. 그 주를 처음 열면 AI 글을 만드느라 몇 초 걸릴 수 있다.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping(value = "/weekly", produces = MediaType.APPLICATION_JSON_VALUE)
    public WeeklyReportV2Response getWeekly(
            @Login LoginPrincipal principal,
            @Parameter(description = "조회할 주에 속한 아무 날", example = "2026-10-28")
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return WeeklyReportV2Response.from(service.getWeekly(principal.userId(), date));
    }
}
