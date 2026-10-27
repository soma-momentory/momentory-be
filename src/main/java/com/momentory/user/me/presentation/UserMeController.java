package com.momentory.user.me.presentation;

import com.momentory.auth.security.Login;
import com.momentory.auth.security.LoginPrincipal;
import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.user.me.application.UserMeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Users", description = "사용자 API")
@RestController
@RequestMapping("/api/v1/users")
public class UserMeController {

    private final UserMeService userMeService;

    public UserMeController(UserMeService userMeService) {
        this.userMeService = userMeService;
    }

    @Operation(summary = "현재 로그인 사용자 조회", description = "현재 Access Token의 사용자 정보를 조회합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "사용자 조회 성공",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UserMeResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증이 없거나 유효하지 않음",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(
                                    name = "AUTHENTICATION_REQUIRED",
                                    value = """
                                            {
                                              "code": "AUTHENTICATION_REQUIRED",
                                              "message": "인증이 필요합니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public UserMeResponse getUserMe(@Login LoginPrincipal principal) {
        return UserMeResponse.from(userMeService.getUserMe(principal.userId()));
    }

    @Operation(summary = "편지 알림 시각 저장", description = "처음 일기를 저장할 때 · 알림 설정에서 고른 바바의 편지 알림 시각과 켜기 여부를 프로필에 저장한다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = LetterTimeResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = {
                    @ExampleObject(name = "INVALID_REQUEST", value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}"),
                    @ExampleObject(name = "letterTimeRequired", value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"letterTime은 필수입니다.\"}"),
                    @ExampleObject(name = "letterTimeFormat", value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"letterTime은 HH:mm 형식이어야 합니다.\"}"),
                    @ExampleObject(name = "notificationEnabledRequired", value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"notificationEnabled는 필수입니다.\"}")
            })),
            @ApiResponse(responseCode = "401", description = "인증이 없거나 유효하지 않음", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}"))),
            @ApiResponse(responseCode = "409", description = "온보딩 전이라 프로필이 없음", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "ONBOARDING_REQUIRED", value = "{\"code\":\"ONBOARDING_REQUIRED\",\"message\":\"온보딩을 먼저 마쳐 주세요.\"}")))
    })
    @PutMapping(value = "/me/letter-time", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public LetterTimeResponse updateLetterTime(@Login LoginPrincipal principal, @Valid @RequestBody LetterTimeRequest request) {
        return LetterTimeResponse.from(userMeService.updateLetterPreference(
                principal.userId(), request.toLetterTime(), request.notificationEnabled()));
    }
}
