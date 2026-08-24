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

    /**
     * 애플 연결 해제(revoke)에 쓸 refresh token. <b>애플 계정에만 있다.</b>
     *
     * <p>탈퇴 시점에는 새로 얻을 수 없는 값이라(사용자가 그때 다시 Sign in with Apple 을
     * 하지는 않는다) 로그인 때 authorization code 를 교환해 받아 둔다.
     *
     * <p>⚠ <b>평문으로 눕는다.</b> 이 토큰으로 할 수 있는 일은 애플 연결을 끊는 것과
     * 수명이 짧은 access token 을 받는 것뿐이고, 그것으로 사용자를 사칭해 로그인할 수는
     * 없다(로그인에는 애플이 서명한 identity token 이 필요하다). 같은 DB 에 일기 본문이
     * 이미 평문으로 있어서, 이 한 칸만 암호화하는 것은 위협 모델을 바꾸지 못한다 —
     * 컬럼 암호화를 도입한다면 그때 함께 덮는다.
     */
    @Column(name = "apple_refresh_token", length = 512)
    private String appleRefreshToken;

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

    public String getAppleRefreshToken() {
        return appleRefreshToken;
    }

    /**
     * 로그인마다 새 authorization code 를 교환하므로 값이 바뀐다.
     *
     * <p><b>빈 값으로 덮지 않는다.</b> 애플이 code 를 주지 않았거나 교환에 실패한
     * 로그인이 이미 갖고 있던 토큰을 지우면, 그 사용자는 탈퇴 때 연결을 끊지 못한다 —
     * 낡은 토큰이라도 없는 것보다 낫다.
     */
    public void updateAppleRefreshToken(String appleRefreshToken) {
        if (appleRefreshToken == null || appleRefreshToken.isBlank()) {
            return;
        }
        this.appleRefreshToken = appleRefreshToken;
    }

}
