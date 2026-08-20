package com.momentory.diary.presentation;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.momentory.auth.security.Login;
import com.momentory.auth.security.LoginPrincipal;
import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.diary.application.DiaryQueryService;
import com.momentory.diary.application.DiaryService;

import jakarta.validation.Valid;

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
 * 일기 보관함 조회 API — 월별 목록과 단건. 일기 생성은 회고 완료 흐름에서 일어나고
 * (여기엔 없다), 그날의 일기 단건은 회고 기준 {@code GET /retrospect/{id}/diary} 로도 볼 수 있다.
 */
@Tag(name = "Diaries", description = "일기 보관함 API")
@RestController
@RequestMapping("/api/v1/diaries")
public class DiaryController {

    private final DiaryQueryService diaryQueryService;
    private final DiaryService diaryService;

    public DiaryController(DiaryQueryService diaryQueryService, DiaryService diaryService) {
        this.diaryQueryService = diaryQueryService;
        this.diaryService = diaryService;
    }

    @Operation(summary = "월별 일기 목록 조회", description = "연·월(KST 기준)에 작성된 일기를 최신순으로 돌려준다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DiaryListResponse.class))),
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
    public DiaryListResponse getMonthly(
            @Login LoginPrincipal principal,
            @Parameter(example = "2026") @RequestParam("year") int year,
            @Parameter(example = "8") @RequestParam("month") int month) {
        return DiaryListResponse.from(diaryQueryService.getMonthly(principal.userId(), year, month));
    }

    @Operation(summary = "일기 단건 조회")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DiaryResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED",
                                    value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "일기 없음",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(name = "DIARY_NOT_FOUND",
                                    value = "{\"code\":\"DIARY_NOT_FOUND\",\"message\":\"일기를 찾을 수 없습니다.\"}")))
    })
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public DiaryResponse getOne(
            @Login LoginPrincipal principal,
            @Parameter(example = "1") @PathVariable Long id) {
        return DiaryResponse.from(diaryQueryService.getOne(principal.userId(), id));
    }

    @Operation(summary = "일기 삭제",
            description = "일기 한 벌만 지운다 — 행동 카드는 자기 보관함에 남고 회고 세션도 남긴다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED",
                                    value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "일기 없음",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(name = "DIARY_NOT_FOUND",
                                    value = "{\"code\":\"DIARY_NOT_FOUND\",\"message\":\"일기를 찾을 수 없습니다.\"}")))
    })
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{id}")
    public void delete(
            @Login LoginPrincipal principal,
            @Parameter(example = "1") @PathVariable Long id) {
        diaryService.delete(principal.userId(), id);
    }

    @Operation(summary = "일기 본문 수정(회고 기준)",
            description = "회고 완료 화면(C6)의 직접 고치기 — 그 회고의 일기 본문만 바꾼다. "
                    + "클라이언트는 일기 id 가 아니라 회고 세션 id 를 들고 있어 그 열쇠로 찾는다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DiaryResponse.class))),
            @ApiResponse(responseCode = "400", description = "본문 누락",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(name = "INVALID_REQUEST",
                                    value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"일기 본문을 입력해주세요.\"}"))),
            @ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED",
                                    value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "일기 없음",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(name = "DIARY_NOT_FOUND",
                                    value = "{\"code\":\"DIARY_NOT_FOUND\",\"message\":\"일기를 찾을 수 없습니다.\"}")))
    })
    @PutMapping(value = "/by-retrospect/{retrospectId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DiaryResponse updateByRetrospect(
            @Login LoginPrincipal principal,
            @Parameter(example = "10") @PathVariable Long retrospectId,
            @Valid @RequestBody DiaryUpdateRequest request) {
        return DiaryResponse.from(
                diaryService.updateOriginalByRetrospect(principal.userId(), retrospectId,
                        request.original()));
    }
}
