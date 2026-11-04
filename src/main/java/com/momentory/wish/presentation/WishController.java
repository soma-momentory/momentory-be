package com.momentory.wish.presentation;

import com.momentory.auth.security.Login;
import com.momentory.auth.security.LoginPrincipal;
import com.momentory.wish.application.WishResults.FloatingWish;
import com.momentory.wish.application.WishResults.MyWishes;
import com.momentory.wish.application.WishResults.Pond;
import com.momentory.wish.application.WishReview;
import com.momentory.wish.application.WishService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Wishes", description = "쉼터 소원 빌기 — 하루 하나, 익명으로 7일 떠 있는 소원 API")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping(path = "/api/v1/wishes", produces = MediaType.APPLICATION_JSON_VALUE)
public class WishController {

    private final WishService wishService;

    public WishController(WishService wishService) {
        this.wishService = wishService;
    }

    @Operation(summary = "연못 조회", description = "남의 소원을 무작위로 최대 10장 돌려준다. 내 소원과 내가 신고한 사람의 소원은 빠지고, 5장보다 적으면 바바의 소원(author=BABA · 음수 id)으로 채운다. 호출할 때마다 다른 소원이 나온다.")
    @GetMapping("/pond")
    public Pond pond(@Login LoginPrincipal principal) {
        return wishService.pond(principal.userId());
    }

    @Operation(summary = "이용 규칙 동의", description = "첫 소원 전에 한 번. 심사와 띄우기는 동의 뒤에만 된다(403 WISH_RULES_REQUIRED).")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/rules-agreement")
    public void agreeToRules(@Login LoginPrincipal principal) {
        wishService.agreeToRules(principal.userId());
    }

    @Operation(summary = "소원 심사", description = "띄우기 전에 글을 살핀다. 아무것도 저장하지 않는다. PASS·EDITED 면 띄울 글과 표를, REJECTED 면 안내 한 줄을, CRISIS 면 빈 값을 돌려준다. 오늘 이미 띄웠으면 409 WISH_DAILY_LIMIT, AI 가 실패하면 503 WISH_REVIEW_UNAVAILABLE.")
    @PostMapping(path = "/review", consumes = MediaType.APPLICATION_JSON_VALUE)
    public WishReview review(@Login LoginPrincipal principal, @Valid @RequestBody WishRequests.Review request) {
        return wishService.review(principal.userId(), request.content());
    }

    @Operation(summary = "소원 띄우기", description = "심사가 돌려준 글과 표로 띄운다. 글이 다르거나 30분이 지난 표는 400 WISH_REVIEW_INVALID.")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public FloatingWish post(@Login LoginPrincipal principal, @Valid @RequestBody WishRequests.Post request) {
        return wishService.post(principal.userId(), request.content(), request.reviewToken());
    }

    @Operation(summary = "내 소원 조회", description = "떠 있는 소원과 지나간 소원. 같이 빈 사람 수는 돌려주지 않고 있었는지만 알린다.")
    @GetMapping("/mine")
    public MyWishes mine(@Login LoginPrincipal principal) {
        return wishService.myWishes(principal.userId());
    }

    @Operation(summary = "같이 빌기", description = "떠 있는 남의 소원에 같이 빈다. 여러 번 불러도 한 번만 남는다.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{wishId}/join")
    public void join(@Login LoginPrincipal principal, @PathVariable Long wishId) {
        wishService.join(principal.userId(), wishId);
    }

    @Operation(summary = "소원 신고", description = "그 작성자의 소원이 나에게 더 보이지 않는다. 서로 다른 3명이 신고하면 연못에서 내린다.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{wishId}/report")
    public void report(@Login LoginPrincipal principal, @PathVariable Long wishId) {
        wishService.report(principal.userId(), wishId);
    }

    @Operation(summary = "신고 되돌리기", description = "잘못 눌렀을 때. 이미 내려간 소원을 다시 올리지는 않는다.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{wishId}/report")
    public void undoReport(@Login LoginPrincipal principal, @PathVariable Long wishId) {
        wishService.undoReport(principal.userId(), wishId);
    }

    @Operation(summary = "내 소원 지우기", description = "연못에서 사라지고 본문을 비운다. 그날의 몫은 돌아오지 않는다.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{wishId}")
    public void delete(@Login LoginPrincipal principal, @PathVariable Long wishId) {
        wishService.delete(principal.userId(), wishId);
    }

    @Operation(summary = "지나간 소원에 답하기", description = "「그 소원, 어떻게 됐어요?」에 답한다.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PutMapping(path = "/{wishId}/outcome", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void answer(@Login LoginPrincipal principal, @PathVariable Long wishId,
            @Valid @RequestBody WishRequests.Answer request) {
        wishService.answer(principal.userId(), wishId, request.outcome());
    }
}
