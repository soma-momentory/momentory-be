package com.momentory.wish.infrastructure;

import com.momentory.wish.domain.WishRuleAgreement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WishRuleAgreementRepository extends JpaRepository<WishRuleAgreement, Long> {
}
