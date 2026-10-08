package com.momentory.treasure.presentation;

import com.momentory.auth.security.Login;
import com.momentory.auth.security.LoginPrincipal;
import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.treasure.application.TreasureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Tag(name = "Treasures", description = "쉼터 오늘의 보물함 API")
@RestController
@RequestMapping("/api/v1/treasures")
public class TreasureController {

    private final TreasureService treasureService;

    public TreasureController(TreasureService treasureService) {
        this.treasureService = treasureService;
    }

    @Operation(summary = "보물 담기", description = "지금이 속한 하루(KST · 04:00 경계)에 소중한 순간 한 줄을 더한다. 하루에 여러 개를 담을 수 있다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "보물 담기 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = TreasureResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = {
                    @ExampleObject(
                            name = "malformedRequestBody",
                            value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "잘못된 요청입니다."
                                    }
                                    """
                    ),
                    @ExampleObject(
                            name = "contentRequiredOrBlank",
                            value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "보물 내용을 입력해주세요."
                                    }
                                    """
                    ),
                    @ExampleObject(
                            name = "contentTooLong",
                            value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "보물 내용은 최대 100자입니다."
                                    }
                                    """
                    )
            })),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(
                    name = "AUTHENTICATION_REQUIRED",
                    value = """
                            {
                              "code": "AUTHENTICATION_REQUIRED",
                              "message": "인증이 필요합니다."
                            }
                            """
            )))
    })
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public TreasureResponse addTreasure(
            @Login LoginPrincipal principal,
            @Valid @RequestBody TreasureRequest request
    ) {
        return TreasureResponse.from(treasureService.addTreasure(principal.userId(), request.content()));
    }

    @Operation(summary = "보물 목록 조회", description = "date 를 주면 그 하루의 보물을 담은 순서대로, 주지 않으면 모든 보물을 최신순으로 돌려준다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = TreasureListResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 날짜 형식", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(
                    name = "invalidDateFormat",
                    value = """
                            {
                              "code": "INVALID_REQUEST",
                              "message": "잘못된 요청입니다."
                            }
                            """
            ))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(
                    name = "AUTHENTICATION_REQUIRED",
                    value = """
                            {
                              "code": "AUTHENTICATION_REQUIRED",
                              "message": "인증이 필요합니다."
                            }
                            """
            )))
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public TreasureListResponse getTreasures(
            @Login LoginPrincipal principal,
            @Parameter(description = "그 하루(KST · 04:00 경계) — 없으면 전부", example = "2026-10-05")
            @RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        Long userId = principal.userId();
        return TreasureListResponse.from(date == null
                ? treasureService.getAllTreasures(userId)
                : treasureService.getTreasuresOn(userId, date));
    }
}
