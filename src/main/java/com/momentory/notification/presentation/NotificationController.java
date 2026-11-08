package com.momentory.notification.presentation;

import com.momentory.auth.security.Login;
import com.momentory.auth.security.LoginPrincipal;
import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.notification.application.NotificationInboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notifications", description = "서버 공지·운영 소식 알림함")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationInboxService service;

    public NotificationController(NotificationInboxService service) {
        this.service = service;
    }

    @Operation(summary = "알림함 목록", description = "발행 시각·ID 최신순. 일반 공지는 가입 이후 발행분을 90일 동안 표시한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "알림 목록", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = NotificationListResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 크기 또는 커서", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = {
                    @ExampleObject(name = "INVALID_REQUEST", value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}"),
                    @ExampleObject(name = "INVALID_CURSOR", value = "{\"code\":\"INVALID_CURSOR\",\"message\":\"알림 목록 커서가 올바르지 않습니다.\"}")
            })),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}")))
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public NotificationListResponse list(@Login LoginPrincipal principal,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(defaultValue = "20") int size) {
        return NotificationListResponse.from(service.list(principal.userId(), cursor, size));
    }

    @Operation(summary = "읽지 않은 알림 여부", description = "현재 보이는 알림 가운데 읽지 않은 항목이 있는지만 반환한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "읽지 않음 여부", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = UnreadResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}")))
    })
    @GetMapping(value = "/unread", produces = MediaType.APPLICATION_JSON_VALUE)
    public UnreadResponse unread(@Login LoginPrincipal principal) {
        return new UnreadResponse(service.hasUnread(principal.userId()));
    }

    @Operation(summary = "알림 하나 읽음 처리", description = "같은 알림을 다시 읽음 처리해도 성공한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "읽음 처리"),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "알림을 찾을 수 없음", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "NOTIFICATION_NOT_FOUND", value = "{\"code\":\"NOTIFICATION_NOT_FOUND\",\"message\":\"알림을 찾을 수 없습니다.\"}")))
    })
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PatchMapping("/{id}/read")
    public void markRead(@Login LoginPrincipal principal, @PathVariable String id) {
        service.markRead(principal.userId(), id);
    }

    @Operation(summary = "표시된 알림 읽음 처리", description = "요청한 ID만 처리하며 보이지 않는 ID는 건너뛴다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "읽음 처리"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "INVALID_REQUEST", value = "{\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}"))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}")))
    })
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping(value = "/read", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void markRead(@Login LoginPrincipal principal, @Valid @RequestBody ReadNotificationsRequest request) {
        service.markRead(principal.userId(), request.ids());
    }
}
