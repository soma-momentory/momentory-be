package com.momentory.actioncard.presentation;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.momentory.auth.security.Login;
import com.momentory.auth.security.LoginPrincipal;
import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.actioncard.application.ActionCardQueryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 행동 카드 보관함 조회 API — 월별 목록과 단건. 카드 생성은 회고 완료 흐름에서 일어나고
 * (여기엔 없다), 그날의 카드 한 장은 회고 기준 {@code GET /retrospect/{id}/diary} 로도 볼 수 있다.
 */
@Tag(name = "ActionCards", description = "행동 카드 보관함 API")
@RestController
@RequestMapping("/api/v1/action-cards")
public class ActionCardController {

    private final ActionCardQueryService actionCardQueryService;

    public ActionCardController(ActionCardQueryService actionCardQueryService) {
        this.actionCardQueryService = actionCardQueryService;
    }

    @Operation(summary = "월별 행동 카드 목록 조회", description = "연·월(KST 기준)에 만든 행동 카드를 최신순으로 돌려준다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ActionCardListResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 연·월",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(name = "INVALID_REQUEST",
                                    value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}"))),
            @ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED",
                                    value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}")))
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ActionCardListResponse getMonthly(
            @Login LoginPrincipal principal,
            @Parameter(example = "2026") @RequestParam("year") int year,
            @Parameter(example = "8") @RequestParam("month") int month) {
        return ActionCardListResponse.from(
                actionCardQueryService.getMonthly(principal.userId(), year, month));
    }

    @Operation(summary = "행동 카드 단건 조회")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ActionCardResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED",
                                    value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "행동 카드 없음",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(name = "ACTION_CARD_NOT_FOUND",
                                    value = "{\"code\":\"ACTION_CARD_NOT_FOUND\",\"message\":\"행동 카드를 찾을 수 없습니다.\"}")))
    })
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ActionCardResponse getOne(
            @Login LoginPrincipal principal,
            @Parameter(example = "1") @PathVariable Long id) {
        return ActionCardResponse.from(actionCardQueryService.getOne(principal.userId(), id));
    }
}
