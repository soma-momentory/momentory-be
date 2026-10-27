package com.momentory.letter.presentation;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.momentory.auth.security.Login;
import com.momentory.auth.security.LoginPrincipal;
import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.letter.application.LetterService;

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
 * 바바의 편지 API — 일기 다음 날 아침(04:00 경계)부터 열린다. 아직 만들지 못한 편지는 열 때 그
 * 자리에서 보충하므로(보통 2~3초 · 드물게 16초쯤) 편지를 돌려주는 조회는 늦게 올 수 있다.
 */
@Tag(name = "Letters", description = "바바의 편지 API")
@RestController
@RequestMapping("/api/v1/letters")
public class LetterController {

    private final LetterService letterService;

    public LetterController(LetterService letterService) {
        this.letterService = letterService;
    }

    @Operation(summary = "지금 열 편지", description = "열 수 있는 가장 최근 편지를 준다(오래된 편지여도 준다). 받은 날과 그다음 날이면 isNew 가 true 다. 편지가 한 통도 없으면 204. 아직 만들지 못한 편지면 그 자리에서 만들어 보통 2~3초, 드물게 16초쯤 걸릴 수 있으니, 홈 아이콘의 점은 이 API 대신 편지 목록의 첫 칸(isNew · openedAt)으로 정한다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = LetterResponse.class))),
            @ApiResponse(responseCode = "204", description = "편지가 한 통도 없음", content = @Content),
            @ApiResponse(responseCode = "404", description = "편지를 만드는 사이 일기가 지워짐", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "LETTER_NOT_FOUND", value = "{\"code\":\"LETTER_NOT_FOUND\",\"message\":\"편지를 찾을 수 없습니다.\"}"))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}")))
    })
    @GetMapping(value = "/current", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LetterResponse> getCurrent(@Login LoginPrincipal principal) {
        return letterService.current(principal.userId())
                .map(view -> ResponseEntity.ok(LetterResponse.from(view)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "편지 목록", description = "옆으로 넘겨 보기 · 홈 아이콘 점 표시용 — 열 수 있는 편지의 날짜를 최신순으로 준다. 편지를 만들지 않아 바로 돌아온다. 내용은 날짜별 조회로 한 통씩 불러온다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = LetterListResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}")))
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public LetterListResponse getLetters(@Login LoginPrincipal principal) {
        return LetterListResponse.from(letterService.list(principal.userId()));
    }

    @Operation(summary = "날짜별 편지", description = "그 날짜 일기의 편지. 아직 열리지 않은 날짜(오늘 · 미래)는 404.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = LetterResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 경로 값", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "INVALID_REQUEST", value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}"))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "편지 없음(아직 열리지 않은 날짜 · 남의 편지 포함)", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "LETTER_NOT_FOUND", value = "{\"code\":\"LETTER_NOT_FOUND\",\"message\":\"편지를 찾을 수 없습니다.\"}")))
    })
    @GetMapping(value = "/{date}", produces = MediaType.APPLICATION_JSON_VALUE)
    public LetterResponse getByDate(@Login LoginPrincipal principal,
            @Parameter(description = "일기 날짜 yyyy-MM-dd", example = "2026-10-26")
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return LetterResponse.from(letterService.byDate(principal.userId(), date));
    }

    @Operation(summary = "편지 열람 기록", description = "처음 연 시각을 남긴다. 여러 번 불러도 처음 값만 남는다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "기록 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 경로 값", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "INVALID_REQUEST", value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}"))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "편지 없음(아직 열리지 않은 날짜 · 남의 편지 포함)", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "LETTER_NOT_FOUND", value = "{\"code\":\"LETTER_NOT_FOUND\",\"message\":\"편지를 찾을 수 없습니다.\"}")))
    })
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{id}/open")
    public void open(@Login LoginPrincipal principal, @Parameter(example = "12") @PathVariable Long id) {
        letterService.open(principal.userId(), id);
    }

    @Operation(summary = "작은 일 고르기", description = "작은 일 하나를 고르거나 바꾸고 「언제」를 남긴다. position 이 null 이면 선택을 푼다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = LetterResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = {
                    @ExampleObject(name = "INVALID_REQUEST", value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}"),
                    @ExampleObject(name = "positionRange", value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"작은 일 칸은 0부터 2까지입니다.\"}"),
                    @ExampleObject(name = "whenLabelTooLong", value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"「언제」는 최대 30자입니다.\"}"),
                    @ExampleObject(name = "LETTER_ACTION_NOT_FOUND", value = "{\"code\":\"LETTER_ACTION_NOT_FOUND\",\"message\":\"편지에 그 작은 일이 없습니다.\"}")
            })),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "편지 또는 일정 없음", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = {
                    @ExampleObject(name = "LETTER_NOT_FOUND", value = "{\"code\":\"LETTER_NOT_FOUND\",\"message\":\"편지를 찾을 수 없습니다.\"}"),
                    @ExampleObject(name = "SCHEDULE_NOT_FOUND", value = "{\"code\":\"SCHEDULE_NOT_FOUND\",\"message\":\"일정을 찾을 수 없습니다.\"}")
            }))
    })
    @PutMapping(value = "/{id}/action", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public LetterResponse chooseAction(@Login LoginPrincipal principal,
            @Parameter(example = "12") @PathVariable Long id,
            @Valid @RequestBody ChooseLetterActionRequest request) {
        return LetterResponse.from(letterService.chooseAction(principal.userId(), id, request.position(),
                request.whenLabel(), request.scheduleId(), request.remindAt()));
    }
}
