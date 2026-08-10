package com.momentory.retrospect.presentation;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.momentory.auth.security.Login;
import com.momentory.auth.security.LoginPrincipal;
import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.retrospect.application.ReplyDto;
import com.momentory.retrospect.application.RetrospectService;
import com.momentory.retrospect.application.metering.UsageSummary;

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

/**
 * AI 회고 채팅 API.
 *
 * <p>세션 상태는 컨트롤러가 들고 있지 않는다 — 매 요청마다 DB에서 꺼내 쓰고, 실제 상태 전이는
 * {@link RetrospectService} 가 조율한다. 모든 엔드포인트는 JWT 인증 사용자 전용이며 사용자는
 * 요청 본문이 아니라 인증 principal 에서 얻는다.
 */
@Tag(name = "Retrospect", description = "AI 회고 채팅 API")
@RestController
@RequestMapping("/api/v1/retrospect")
public class RetrospectController {

    private final RetrospectService retrospectService;

    public RetrospectController(RetrospectService retrospectService) {
        this.retrospectService = retrospectService;
    }

    @Operation(summary = "회고 시작", description = "일정·감정을 골라 세션을 만들고 첫 메시지를 받는다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "회고 시작 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = StartRetrospectResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청(감정 키 오류 등)", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(
                    name = "INVALID_REQUEST",
                    value = """
                            {
                              "code": "INVALID_REQUEST",
                              "message": "currentEmotion은 필수입니다."
                            }
                            """))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(
                    name = "AUTHENTICATION_REQUIRED",
                    value = """
                            {
                              "code": "AUTHENTICATION_REQUIRED",
                              "message": "인증이 필요합니다."
                            }
                            """)))
    })
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public StartRetrospectResponse start(
            @Login LoginPrincipal principal,
            @Valid @RequestBody StartRetrospectRequest request) {
        return StartRetrospectResponse.from(
                retrospectService.start(principal.userId(), request.toDomain()));
    }

    @Operation(summary = "회고 한 턴 진행", description = "자유 텍스트·선택지·슬라이더 값 중 그 턴에 맞는 것을 보낸다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "턴 진행 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ReplyDto.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(
                    name = "INVALID_REQUEST",
                    value = """
                            {
                              "code": "INVALID_REQUEST",
                              "message": "잘못된 요청입니다."
                            }
                            """))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(
                    name = "AUTHENTICATION_REQUIRED",
                    value = """
                            {
                              "code": "AUTHENTICATION_REQUIRED",
                              "message": "인증이 필요합니다."
                            }
                            """))),
            @ApiResponse(responseCode = "404", description = "세션을 찾을 수 없음", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(
                    name = "RETROSPECT_SESSION_NOT_FOUND",
                    value = """
                            {
                              "code": "RETROSPECT_SESSION_NOT_FOUND",
                              "message": "회고 세션을 찾을 수 없습니다."
                            }
                            """)))
    })
    @PostMapping(value = "/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ReplyDto sendMessage(
            @Login LoginPrincipal principal,
            @Parameter(example = "1") @PathVariable Long id,
            @Valid @RequestBody TurnRequest request) {
        return retrospectService.handle(principal.userId(), id, request.toDomain());
    }

    @Operation(summary = "회고 세션 LLM 사용량 요약", description = "이번 세션의 유료 호출·토큰·추정 비용.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "사용량 조회 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = UsageSummary.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(
                    name = "AUTHENTICATION_REQUIRED",
                    value = """
                            {
                              "code": "AUTHENTICATION_REQUIRED",
                              "message": "인증이 필요합니다."
                            }
                            """))),
            @ApiResponse(responseCode = "404", description = "세션을 찾을 수 없음", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(
                    name = "RETROSPECT_SESSION_NOT_FOUND",
                    value = """
                            {
                              "code": "RETROSPECT_SESSION_NOT_FOUND",
                              "message": "회고 세션을 찾을 수 없습니다."
                            }
                            """)))
    })
    @GetMapping(value = "/{id}/usage", produces = MediaType.APPLICATION_JSON_VALUE)
    public UsageSummary usage(
            @Login LoginPrincipal principal,
            @Parameter(example = "1") @PathVariable Long id) {
        return retrospectService.usage(principal.userId(), id);
    }
}
