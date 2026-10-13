package com.momentory.inquiry.domain;

import com.momentory.common.persistence.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 앱 안에서 보낸 문의 하나.
 *
 * <p>답변은 앱이 아니라 메일로 한다 — 받을 이메일은 선택이다. 처리 상태({@code status} 열)는 개발자가 DB 에서
 * 직접 바꾸므로 엔티티에 두지 않는다(새 문의는 DB 기본값 {@code RECEIVED}). 탈퇴하면 함께 지워진다.
 */
@Entity
@Table(name = "inquiries")
public class Inquiry extends BaseTimeEntity {

    public static final int CONTENT_MAX_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InquiryCategory category;

    @Column(nullable = false, length = CONTENT_MAX_LENGTH)
    private String content;

    @Column(name = "reply_email", length = 254)
    private String replyEmail;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private InquiryPlatform platform;

    @Column(name = "os_version", length = 30)
    private String osVersion;

    @Column(name = "app_version", length = 30)
    private String appVersion;

    protected Inquiry() {
    }

    private Inquiry(Long userId, InquiryCategory category, String content, String replyEmail,
                    InquiryPlatform platform, String osVersion, String appVersion) {
        this.userId = userId;
        this.category = category;
        this.content = content;
        this.replyEmail = replyEmail;
        this.platform = platform;
        this.osVersion = osVersion;
        this.appVersion = appVersion;
    }

    public static Inquiry create(Long userId, InquiryCategory category, String content, String replyEmail,
                                 InquiryPlatform platform, String osVersion, String appVersion) {
        return new Inquiry(userId, category, content, replyEmail, platform, osVersion, appVersion);
    }

    public Long getId() {
        return id;
    }

    public InquiryCategory getCategory() {
        return category;
    }

    public String getContent() {
        return content;
    }

    public String getReplyEmail() {
        return replyEmail;
    }

    public InquiryPlatform getPlatform() {
        return platform;
    }

    public String getOsVersion() {
        return osVersion;
    }

    public String getAppVersion() {
        return appVersion;
    }
}
