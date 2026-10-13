package com.momentory.inquiry.application;

import com.momentory.inquiry.domain.Inquiry;

import java.time.Instant;

public record InquiryResult(Long id, Instant createdAt) {

    public static InquiryResult from(Inquiry inquiry) {
        return new InquiryResult(inquiry.getId(), inquiry.getCreatedAt());
    }
}
