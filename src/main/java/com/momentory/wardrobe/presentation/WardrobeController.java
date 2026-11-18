package com.momentory.wardrobe.presentation;

import com.momentory.auth.security.Login;
import com.momentory.auth.security.LoginPrincipal;
import com.momentory.common.presentation.ApiErrorResponse;
import com.momentory.wardrobe.application.WardrobeResults.TangerineCheckIn;
import com.momentory.wardrobe.application.WardrobeResults.WardrobeOutfit;
import com.momentory.wardrobe.application.WardrobeResults.PurchaseResult;
import com.momentory.wardrobe.application.WardrobeResults.WardrobeView;
import com.momentory.wardrobe.application.WardrobeService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Wardrobe", description = "바바 꾸미기 — 옷장 · 귤 API")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping(path = "/api/v1/wardrobe", produces = MediaType.APPLICATION_JSON_VALUE)
public class WardrobeController {

    private static final String UNAUTHORIZED = """
            {
              "code": "AUTHENTICATION_REQUIRED",
              "message": "인증이 필요합니다."
            }
            """;
    private static final String NOT_FOUND = """
            {
              "code": "WARDROBE_ITEM_NOT_FOUND",
              "message": "아이템을 찾을 수 없습니다."
            }
            """;

    private final WardrobeService wardrobeService;

    public WardrobeController(WardrobeService wardrobeService) {
        this.wardrobeService = wardrobeService;
    }

    @Operation(summary = "옷장 조회", description = "가진 귤 · 지금 입은 것 · 상점 아이템(무료 · 판매 중 · 이미 가진 것). 귤을 정산하지 않는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = WardrobeView.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = UNAUTHORIZED)))
    })
    @GetMapping
    public WardrobeView wardrobe(@Login LoginPrincipal principal) {
        return wardrobeService.wardrobe(principal.userId());
    }

    @Operation(summary = "귤 정산", description = "앱을 열 때마다 부른다. 오늘 출석(이레 칸에 따라 10 · 10 · 20 · 20 · 30 · 40 · 50), 어제 · 오늘의 일기(30) · 일정 모두 완료(10) · 쉼터 한 곳 끝까지 다녀오기(10) · 편지 열어 보기(10)를 확인해 아직 받지 않은 것만 준다. 여러 번 불러도 한 번씩만 준다. quiet 가 참이면 오늘 위기 안내가 있었던 날이라 앱이 알리지 않는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "정산 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = TangerineCheckIn.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = UNAUTHORIZED)))
    })
    @PostMapping("/check-in")
    public TangerineCheckIn checkIn(@Login LoginPrincipal principal) {
        return wardrobeService.checkIn(principal.userId());
    }

    @Operation(summary = "옷장 첫 선물 받기", description = "옷장에 처음 온 사람에게 귤 100개를 준다. 평생 한 번이고, 이미 받았으면 rewards 가 빈 목록이다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "받기 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = TangerineCheckIn.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = UNAUTHORIZED)))
    })
    @PostMapping("/welcome")
    public TangerineCheckIn welcome(@Login LoginPrincipal principal) {
        return wardrobeService.welcome(principal.userId());
    }

    @Operation(summary = "입히기", description = "칸마다 아이템 id 하나, 벗으려면 null. 보낸 그대로 바뀐다. 무료이거나 산 아이템만 입는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "입히기 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = WardrobeOutfit.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "itemIdTooLong", value = """
                    {
                      "code": "INVALID_REQUEST",
                      "message": "아이템 id 가 너무 깁니다."
                    }
                    """))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "가지지 않은 아이템", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "WARDROBE_ITEM_NOT_OWNED", value = """
                    {
                      "code": "WARDROBE_ITEM_NOT_OWNED",
                      "message": "가지지 않은 아이템입니다."
                    }
                    """))),
            @ApiResponse(responseCode = "404", description = "없는 아이템이거나 칸이 다름", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "WARDROBE_ITEM_NOT_FOUND", value = NOT_FOUND)))
    })
    @PutMapping(path = "/outfit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public WardrobeOutfit wear(@Login LoginPrincipal principal, @Valid @RequestBody OutfitRequest request) {
        return wardrobeService.wear(principal.userId(), request.toOutfit());
    }

    @Operation(summary = "귤로 사기", description = "잔액 확인과 차감을 한 번에 한다. 이미 가진 아이템이면 귤을 쓰지 않고 지금 잔액을 돌려준다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "사기 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = PurchaseResult.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "AUTHENTICATION_REQUIRED", value = UNAUTHORIZED))),
            @ApiResponse(responseCode = "404", description = "없거나 팔지 않는 아이템", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "WARDROBE_ITEM_NOT_FOUND", value = NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "귤이 모자람", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class), examples = @ExampleObject(name = "TANGERINE_NOT_ENOUGH", value = """
                    {
                      "code": "TANGERINE_NOT_ENOUGH",
                      "message": "귤이 모자랍니다."
                    }
                    """)))
    })
    @PostMapping("/items/{itemId}/purchase")
    public PurchaseResult purchase(@Login LoginPrincipal principal, @PathVariable String itemId) {
        return wardrobeService.purchase(principal.userId(), itemId);
    }
}
