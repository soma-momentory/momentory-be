package com.momentory.auth.kakao.presentation;

import com.momentory.auth.kakao.application.KakaoLoginResult;
import com.momentory.auth.kakao.application.KakaoLoginService;
import com.momentory.common.presentation.ApiErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication", description = "인증 API")
@RestController
@RequestMapping("/api/v1/auth")
public class KakaoLoginController {

    private final KakaoLoginService kakaoLoginService;

    public KakaoLoginController(KakaoLoginService kakaoLoginService) {
        this.kakaoLoginService = kakaoLoginService;
    }

    @Operation(
            summary = "카카오 Native 로그인",
            description = "React Native 카카오 Native SDK Access Token을 검증하고 모멘토리 토큰을 발급합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "로그인 성공",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = KakaoLoginResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "accessToken": "발급된 모멘토리 Access Token",
                                      "refreshToken": "발급된 모멘토리 Refresh Token",
                                      "tokenType": "Bearer",
                                      "accessTokenExpiresIn": 1800,
                                      "userId": 1,
                                      "onboardingRequired": true
                                    }
                                    """)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = {
                                    @ExampleObject(
                                            name = "validationError",
                                            value = """
                                                    {
                                                      "code": "INVALID_REQUEST",
                                                      "message": "accessToken은 필수입니다."
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "unreadableRequest",
                                            value = """
                                                    {
                                                      "code": "INVALID_REQUEST",
                                                      "message": "잘못된 요청입니다."
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "KAKAO_EMAIL_CONSENT_REQUIRED",
                                            value = """
                                                    {
                                                      "code": "KAKAO_EMAIL_CONSENT_REQUIRED",
                                                      "message": "카카오계정 이메일 제공 동의가 필요합니다."
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "KAKAO_EMAIL_UNAVAILABLE",
                                            value = """
                                                    {
                                                      "code": "KAKAO_EMAIL_UNAVAILABLE",
                                                      "message": "유효하고 인증된 카카오계정 이메일이 필요합니다."
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "카카오 토큰 검증 실패",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = {
                                    @ExampleObject(
                                            name = "KAKAO_TOKEN_INVALID",
                                            value = """
                                                    {
                                                      "code": "KAKAO_TOKEN_INVALID",
                                                      "message": "카카오 인증에 실패했습니다."
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "KAKAO_APP_ID_MISMATCH",
                                            value = """
                                                    {
                                                      "code": "KAKAO_APP_ID_MISMATCH",
                                                      "message": "카카오 인증에 실패했습니다."
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "KAKAO_USER_ID_MISMATCH",
                                            value = """
                                                    {
                                                      "code": "KAKAO_USER_ID_MISMATCH",
                                                      "message": "카카오 인증에 실패했습니다."
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "502",
                    description = "카카오 API 서버 또는 응답 오류",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = {
                                    @ExampleObject(
                                            name = "KAKAO_API_SERVER_ERROR",
                                            value = """
                                                    {
                                                      "code": "KAKAO_API_SERVER_ERROR",
                                                      "message": "카카오 서비스에 일시적인 오류가 발생했습니다."
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "KAKAO_API_RESPONSE_ERROR",
                                            value = """
                                                    {
                                                      "code": "KAKAO_API_RESPONSE_ERROR",
                                                      "message": "카카오 서비스 응답을 처리할 수 없습니다."
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "카카오 API 네트워크 오류",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(
                                    name = "KAKAO_API_NETWORK_ERROR",
                                    value = """
                                            {
                                              "code": "KAKAO_API_NETWORK_ERROR",
                                              "message": "카카오 서비스에 연결할 수 없습니다."
                                            }
                                            """
                            )
                    )
            )
    })
    @PostMapping(value = "/kakao", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public KakaoLoginResponse login(@Valid @RequestBody KakaoLoginRequest request) {
        KakaoLoginResult result = kakaoLoginService.login(request.accessToken());
        return KakaoLoginResponse.from(result);
    }
}
