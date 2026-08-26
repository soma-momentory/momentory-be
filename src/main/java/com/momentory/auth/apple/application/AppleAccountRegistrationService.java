package com.momentory.auth.apple.application;

import com.momentory.user.domain.OAuthAccount;
import com.momentory.user.domain.OAuthProvider;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.OAuthAccountRepository;
import com.momentory.user.infrastructure.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppleAccountRegistrationService {

    private final UserRepository userRepository;
    private final OAuthAccountRepository oauthAccountRepository;

    public AppleAccountRegistrationService(
            UserRepository userRepository,
            OAuthAccountRepository oauthAccountRepository
    ) {
        this.userRepository = userRepository;
        this.oauthAccountRepository = oauthAccountRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long createUserAndAccount(String providerUserId, String email) {
        // Apple 은 최초 승인 이후 이메일을 다시 주지 않을 수 있다. 계정의 신원은
        // 검증된 providerUserId(sub)로 정하고, 이메일은 제공된 경우에만 보관한다.
        User user = userRepository.save(email == null ? User.create() : User.create(email));
        OAuthAccount account = OAuthAccount.create(user, OAuthProvider.APPLE, providerUserId);
        oauthAccountRepository.saveAndFlush(account);
        return user.getId();
    }
}
