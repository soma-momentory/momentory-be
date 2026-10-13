package com.momentory.inquiry.application;

import com.momentory.inquiry.domain.Inquiry;
import com.momentory.inquiry.domain.InquiryCategory;
import com.momentory.inquiry.domain.InquiryPlatform;
import com.momentory.inquiry.infrastructure.InquiryRepository;
import com.momentory.user.application.AuthenticatedUserNotFoundException;
import com.momentory.user.infrastructure.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InquiryService {

    private final InquiryRepository inquiryRepository;
    private final UserRepository userRepository;

    public InquiryService(InquiryRepository inquiryRepository, UserRepository userRepository) {
        this.inquiryRepository = inquiryRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public InquiryResult addInquiry(Long userId, InquiryCategory category, String content, String replyEmail,
                                    InquiryPlatform platform, String osVersion, String appVersion) {
        userRepository.findById(userId)
                .orElseThrow(AuthenticatedUserNotFoundException::new);
        Inquiry saved = inquiryRepository.saveAndFlush(
                Inquiry.create(userId, category, content, replyEmail, platform, osVersion, appVersion));
        return InquiryResult.from(saved);
    }
}
