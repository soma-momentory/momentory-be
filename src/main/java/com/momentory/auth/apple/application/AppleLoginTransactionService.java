package com.momentory.auth.apple.application;

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

import java.util.Optional;

@Service
public class AppleLoginTransactionService {

    private final OAuthAccountRepository oauthAccountRepository;
    private final UserRepository userRepository;
    private final AppleAccountRegistrationService registrationService;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenIssuer refreshTokenIssuer;
    private final RefreshTokenRepository refreshTokenRepository;

    public AppleLoginTransactionService(
            OAuthAccountRepository oauthAccountRepository,
            UserRepository userRepository,
            AppleAccountRegistrationService registrationService,
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
    public AppleLoginResult login(AppleUserInfo appleUserInfo) {
        Long userId = findExistingUserId(appleUserInfo.providerUserId())
                .orElseGet(() -> createOrFindUserId(appleUserInfo));
        User user = userRepository.findById(userId)
                .orElseThrow(IllegalStateException::new);
        user.updateEmail(appleUserInfo.email());

        IssuedAccessToken accessToken = accessTokenIssuer.issue(user.getId(), user.getRole());
        IssuedRefreshToken refreshToken = refreshTokenIssuer.issue();
        refreshTokenRepository.save(RefreshToken.create(user, refreshToken.hash(), refreshToken.expiresAt()));

        return new AppleLoginResult(
                accessToken.value(),
                refreshToken.value(),
                accessToken.expiresIn(),
                user.getId(),
                user.requiresOnboarding()
        );
    }

    private Optional<Long> findExistingUserId(String providerUserId) {
        return oauthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.APPLE, providerUserId)
                .map(OAuthAccount::getUser)
                .map(User::getId);
    }

    private Long createOrFindUserId(AppleUserInfo appleUserInfo) {
        try {
            return registrationService.createUserAndAccount(
                    appleUserInfo.providerUserId(),
                    appleUserInfo.email()
            );
        } catch (DataIntegrityViolationException exception) {
            return findExistingUserId(appleUserInfo.providerUserId())
                    .orElseThrow(() -> exception);
        }
    }
}
