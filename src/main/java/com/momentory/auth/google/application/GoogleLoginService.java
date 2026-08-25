package com.momentory.auth.google.application;

import com.momentory.auth.google.infrastructure.GoogleIdTokenVerifier;
import org.springframework.stereotype.Service;

@Service
public class GoogleLoginService {

    private final GoogleIdTokenVerifier idTokenVerifier;
    private final GoogleLoginTransactionService transactionService;

    public GoogleLoginService(
            GoogleIdTokenVerifier idTokenVerifier,
            GoogleLoginTransactionService transactionService
    ) {
        this.idTokenVerifier = idTokenVerifier;
        this.transactionService = transactionService;
    }

    /**
     * <b>검증은 트랜잭션 밖이다</b> — 애플과 같은 이유다. 구글 JWKS 를 부르는 동안
     * DB 커넥션을 잡고 있을 이유가 없고, 검증에 실패하면 트랜잭션을 열 일도 없다.
     */
    public GoogleLoginResult login(String idToken) {
        GoogleUserInfo googleUserInfo = idTokenVerifier.verify(idToken);
        return transactionService.login(googleUserInfo);
    }
}
