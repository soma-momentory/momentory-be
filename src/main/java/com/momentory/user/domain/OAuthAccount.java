package com.momentory.user.domain;

import com.momentory.common.persistence.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.Objects;

@Entity
@Table(
        name = "oauth_accounts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_oauth_accounts_provider_provider_user_id",
                columnNames = {"provider", "provider_user_id"}
        )
)
public class OAuthAccount extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_oauth_accounts_user")
    )
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OAuthProvider provider;

    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    protected OAuthAccount() {
    }

    private OAuthAccount(User user, OAuthProvider provider, String providerUserId) {
        this.user = Objects.requireNonNull(user);
        this.provider = Objects.requireNonNull(provider);
        this.providerUserId = requireProviderUserId(providerUserId);
    }

    public static OAuthAccount create(User user, OAuthProvider provider, String providerUserId) {
        return new OAuthAccount(user, provider, providerUserId);
    }

    private String requireProviderUserId(String providerUserId) {
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new IllegalArgumentException("Provider user ID must not be blank.");
        }
        return providerUserId;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public OAuthProvider getProvider() {
        return provider;
    }

    public String getProviderUserId() {
        return providerUserId;
    }

}
