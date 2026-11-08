package com.momentory.user.onboarding.presentation;

import com.momentory.auth.security.Login;
import com.momentory.auth.security.LoginPrincipal;
import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.user.onboarding.application.CompleteOnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Users", description = "사용자 API")
@RestController
@RequestMapping("/api/v2/users/me")
public class UserOnboardingV2Controller {

    private final CompleteOnboardingService completeOnboardingService;

    public UserOnboardingV2Controller(CompleteOnboardingService completeOnboardingService) {
        this.completeOnboardingService = completeOnboardingService;
    }

    @Operation(
            summary = "새 온보딩 완료",
            description = "받은 항목만 고친다. 관심사 · 쉬는 방법 · 기타 설명은 받지 않고, 있던 값을 그대로 둔다."
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "온보딩 저장 성공",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CompleteOnboardingV2Response.class)
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
                                                      "message": "nickname은 필수입니다."
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
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
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
    @PutMapping(value = "/onboarding", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CompleteOnboardingV2Response complete(
            @Login LoginPrincipal principal,
            @Valid @RequestBody CompleteOnboardingV2Request request
    ) {
        return CompleteOnboardingV2Response.from(completeOnboardingService.completeLatest(
                principal.userId(),
                request.nickname(),
                request.age(),
                request.gender(),
                request.toReflectionTime(),
                request.calendarIntegrationEnabled(),
                request.notificationEnabled(),
                request.moodChange(),
                request.knowWhy(),
                request.dayEnd(),
                request.diaryHistory(),
                request.goals()
        ));
    }
}
