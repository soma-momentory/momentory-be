package com.momentory.auth.kakao.application;

import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.auth.token.application.IssuedAccessToken;
import com.momentory.auth.token.application.IssuedRefreshToken;
import com.momentory.auth.token.application.RefreshTokenIssuer;
import com.momentory.auth.token.domain.RefreshToken;
import com.momentory.auth.token.infrastructure.RefreshTokenRepository;
import com.momentory.user.domain.OAuthAccount;
import com.momentory.user.domain.OAuthProvider;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.OAuthAccountRepository;
import com.momentory.user.infrastructure.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KakaoLoginTransactionService {

    private final OAuthAccountRepository oauthAccountRepository;
    private final UserRepository userRepository;
    private final OAuthAccountRegistrationService registrationService;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenIssuer refreshTokenIssuer;
    private final RefreshTokenRepository refreshTokenRepository;

    public KakaoLoginTransactionService(
            OAuthAccountRepository oauthAccountRepository,
            UserRepository userRepository,
            OAuthAccountRegistrationService registrationService,
            AccessTokenIssuer accessTokenIssuer,
            RefreshTokenIssuer refreshTokenIssuer,
            RefreshTokenRepository refreshTokenRepository
    ) {
        this.oauthAccountRepository = oauthAccountRepository;
        this.userRepository = userRepository;
        this.registrationService = registrationService;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenIssuer = refreshTokenIssuer;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional
    public KakaoLoginResult login(KakaoUserInfo kakaoUserInfo) {
        Long userId = findExistingUserId(kakaoUserInfo.providerUserId())
                .orElseGet(() -> createOrFindUserId(kakaoUserInfo));
        User user = userRepository.findById(userId)
                .orElseThrow(IllegalStateException::new);
        user.updateEmail(kakaoUserInfo.email());

        IssuedAccessToken accessToken = accessTokenIssuer.issue(user.getId(), user.getRole());
        IssuedRefreshToken refreshToken = refreshTokenIssuer.issue();
        refreshTokenRepository.save(RefreshToken.create(user, refreshToken.hash(), refreshToken.expiresAt()));

        return new KakaoLoginResult(
                accessToken.value(),
                refreshToken.value(),
                accessToken.expiresIn(),
                user.getId(),
                user.requiresOnboarding()
        );
    }

    private java.util.Optional<Long> findExistingUserId(String providerUserId) {
        return oauthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.KAKAO, providerUserId)
                .map(OAuthAccount::getUser)
                .map(User::getId);
    }

    private Long createOrFindUserId(KakaoUserInfo kakaoUserInfo) {
        try {
            return registrationService.createUserAndAccount(
                    kakaoUserInfo.providerUserId(),
                    kakaoUserInfo.email()
            );
        } catch (DataIntegrityViolationException exception) {
            return findExistingUserId(kakaoUserInfo.providerUserId())
                    .orElseThrow(() -> exception);
        }
    }
}
