package com.momentory.auth.google.application;

import com.momentory.user.domain.OAuthAccount;
import com.momentory.user.domain.OAuthProvider;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.OAuthAccountRepository;
import com.momentory.user.infrastructure.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 새 계정을 <b>별도 트랜잭션</b>에서 세운다.
 *
 * <p>같은 사용자의 첫 로그인이 동시에 둘 들어오면 하나는 유니크 제약에 걸린다
 * ({@code uk_oauth_accounts_provider_provider_user_id}). 바깥 트랜잭션에서 그것을
 * 맞으면 트랜잭션이 통째로 롤백 표시가 붙어 <b>「이미 있으니 그걸 쓰자」로
 * 되돌아갈 수 없다.</b> 그래서 여기만 떼어 낸다
 * ({@link GoogleLoginTransactionService#createOrFindUserId}).
 */
@Service
public class GoogleAccountRegistrationService {

    private final UserRepository userRepository;
    private final OAuthAccountRepository oauthAccountRepository;

    public GoogleAccountRegistrationService(
            UserRepository userRepository,
            OAuthAccountRepository oauthAccountRepository
    ) {
        this.userRepository = userRepository;
        this.oauthAccountRepository = oauthAccountRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long createUserAndAccount(String providerUserId, String email) {
        User user = userRepository.save(User.create(email));
        OAuthAccount account = OAuthAccount.create(user, OAuthProvider.GOOGLE, providerUserId);
        oauthAccountRepository.saveAndFlush(account);
        return user.getId();
    }
}
