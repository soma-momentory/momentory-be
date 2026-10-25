package com.momentory.user.infrastructure;

import com.momentory.user.domain.OnboardingResponse;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnboardingResponseRepository extends JpaRepository<OnboardingResponse, OnboardingResponse.Id> {
}
