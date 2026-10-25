package com.momentory.user.domain;

import com.momentory.common.persistence.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;

@Entity
@Table(name = "users")
public class User extends BaseTimeEntity {

    /** 지금 앱이 보여 주는 온보딩 판. 이보다 낮은 판을 마친 사용자는 새 앱에서 온보딩을 다시 한다. */
    public static final int LATEST_ONBOARDING_VERSION = 2;
    private static final int LEGACY_ONBOARDING_VERSION = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(name = "onboarding_completed", nullable = false)
    private boolean onboardingCompleted;

    @Column(name = "onboarding_version", nullable = false)
    private int onboardingVersion;

    @Column(length = 320)
    private String email;

    protected User() {
    }

    private User(UserRole role, String email) {
        this.role = role;
        this.email = email;
        this.onboardingCompleted = false;
    }

    public static User create() {
        return new User(UserRole.USER, null);
    }

    public static User create(String email) {
        return new User(UserRole.USER, requireEmail(email));
    }

    public void updateEmail(String email) {
        this.email = requireEmail(email);
    }

    /** 옛 온보딩(v1 API) 완료. 옛 앱은 판을 모르므로 이미 오른 판을 낮추지 않는다. */
    public void completeOnboarding() {
        onboardingCompleted = true;
        onboardingVersion = Math.max(onboardingVersion, LEGACY_ONBOARDING_VERSION);
    }

    public void completeLatestOnboarding() {
        onboardingCompleted = true;
        onboardingVersion = LATEST_ONBOARDING_VERSION;
    }

    public boolean requiresOnboarding() {
        return !onboardingCompleted;
    }

    public int getOnboardingVersion() {
        return onboardingVersion;
    }

    public Long getId() {
        return id;
    }

    public UserRole getRole() {
        return role;
    }

    public String getEmail() {
        return email;
    }

    private static String requireEmail(String email) {
        String requiredEmail = Objects.requireNonNull(email, "email must not be null").trim();
        if (requiredEmail.isEmpty() || requiredEmail.length() > 320) {
            throw new IllegalArgumentException("email must be between 1 and 320 characters");
        }
        return requiredEmail;
    }
}
