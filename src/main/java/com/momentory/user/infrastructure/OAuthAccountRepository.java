package com.momentory.user.infrastructure;

import com.momentory.user.domain.OAuthAccount;
import com.momentory.user.domain.OAuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, Long> {

    Optional<OAuthAccount> findByProviderAndProviderUserId(OAuthProvider provider, String providerUserId);

    Optional<OAuthAccount> findByUser_IdAndProvider(Long userId, OAuthProvider provider);
}
