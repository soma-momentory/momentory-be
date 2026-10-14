package com.momentory.inquiry.presentation;

import com.momentory.inquiry.application.InquiryResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record InquiryResponse(
        @Schema(description = "문의 id", example = "1") Long id,
        @Schema(description = "받은 시각", example = "2026-10-13T11:23:47.850Z") Instant createdAt
) {

    static InquiryResponse from(InquiryResult result) {
        return new InquiryResponse(result.id(), result.createdAt());
    }
}
