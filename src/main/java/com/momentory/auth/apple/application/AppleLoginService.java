package com.momentory.auth.apple.application;

import com.momentory.auth.apple.infrastructure.AppleIdentityTokenVerifier;
import org.springframework.stereotype.Service;

@Service
public class AppleLoginService {

    private final AppleIdentityTokenVerifier identityTokenVerifier;
    private final AppleLoginTransactionService transactionService;

    public AppleLoginService(
            AppleIdentityTokenVerifier identityTokenVerifier,
            AppleLoginTransactionService transactionService
    ) {
        this.identityTokenVerifier = identityTokenVerifier;
        this.transactionService = transactionService;
    }

    public AppleLoginResult login(String identityToken, String nonce, String authorizationCode) {
        AppleUserInfo appleUserInfo = identityTokenVerifier.verify(identityToken, nonce);
        return transactionService.login(appleUserInfo, authorizationCode);
    }
}
