package com.momentory.inquiry.presentation;

import com.momentory.inquiry.domain.Inquiry;
import com.momentory.inquiry.domain.InquiryCategory;
import com.momentory.inquiry.domain.InquiryPlatform;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record InquiryRequest(
        @Schema(description = "문의 종류", example = "BUG")
        @NotNull(message = "문의 종류를 골라주세요.")
        InquiryCategory category,
        @Schema(description = "문의 내용(최대 2000자)", example = "일기를 저장하면 앱이 꺼져요.")
        @NotBlank(message = "문의 내용을 입력해주세요.")
        @Size(max = Inquiry.CONTENT_MAX_LENGTH, message = "문의 내용은 최대 2000자입니다.")
        String content,
        @Schema(description = "답변 받을 이메일 — 선택", nullable = true, example = "momentory@example.com")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 254, message = "이메일 형식이 올바르지 않습니다.")
        String replyEmail,
        @Schema(description = "보낸 기기의 운영체제 — 앱이 채운다", nullable = true, example = "IOS")
        InquiryPlatform platform,
        @Schema(description = "운영체제 버전 — 앱이 채운다", nullable = true, example = "18.6")
        @Size(max = 30, message = "기기 정보는 최대 30자입니다.")
        String osVersion,
        @Schema(description = "앱 버전 — 앱이 채운다", nullable = true, example = "1.2.0")
        @Size(max = 30, message = "기기 정보는 최대 30자입니다.")
        String appVersion
) {

    public InquiryRequest {
        content = content == null ? null : content.strip();
        replyEmail = replyEmail == null || replyEmail.isBlank() ? null : replyEmail.strip();
    }
}
