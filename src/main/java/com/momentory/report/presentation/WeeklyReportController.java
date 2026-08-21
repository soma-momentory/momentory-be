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
import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.report.application.WeeklyReportService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 주간 리포트 조회 API — 한 주(월~일, KST)에 한 벌. */
@Tag(name = "Reports", description = "주간 리포트 API")
@RestController
@RequestMapping("/api/v1/reports")
public class WeeklyReportController {

    private final WeeklyReportService weeklyReportService;

    public WeeklyReportController(WeeklyReportService weeklyReportService) {
        this.weeklyReportService = weeklyReportService;
    }

    @Operation(summary = "주간 리포트 조회",
            description = "그 주(월~일, KST)의 마음 일곱 칸과 요약 멘트, 일정·행동 카드·일기 셈을 돌려준다. "
                    + "date 는 그 주에 속한 아무 날이나 넣으면 되고, 서버가 해당 주의 월요일로 맞춘다. "
                    + "생략하면 오늘(KST)이 속한 주다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = WeeklyReportResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 날짜 형식",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(name = "invalidDateFormat",
                                    value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}"))),
            @ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED",
                                    value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}")))
    })
    @GetMapping(value = "/weekly", produces = MediaType.APPLICATION_JSON_VALUE)
    public WeeklyReportResponse getWeekly(
            @Login LoginPrincipal principal,
            @Parameter(description = "조회할 주에 속한 아무 날 — 생략하면 오늘(KST)", example = "2026-08-21")
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return WeeklyReportResponse.from(weeklyReportService.getWeekly(principal.userId(), date));
    }
}
