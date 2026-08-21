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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(name = "onboarding_completed", nullable = false)
    private boolean onboardingCompleted;

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

    public void completeOnboarding() {
        onboardingCompleted = true;
    }

    public boolean requiresOnboarding() {
        return !onboardingCompleted;
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
