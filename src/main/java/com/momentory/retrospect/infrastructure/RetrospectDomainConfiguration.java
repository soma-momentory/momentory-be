package com.momentory.retrospect.infrastructure;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import com.momentory.retrospect.domain.NeedActionCatalog;
import com.momentory.retrospect.domain.safety.SafetyPolicy;
import com.momentory.retrospect.domain.strategy.StrategyPolicy;

import tools.jackson.databind.ObjectMapper;

/**
 * 회고 도메인 객체의 빈 등록.
 *
 * <p>도메인 클래스에는 스프링 애노테이션을 달지 않는다 — 프레임워크를 모르는 순수 자바로 두고,
 * 조립만 이 설정 계층이 한다. 덕분에 도메인 단위 테스트는 스프링 컨텍스트 없이 돈다.
 */
@Configuration
public class RetrospectDomainConfiguration {

    @Bean
    SafetyPolicy safetyPolicy() {
        return new SafetyPolicy();
    }

    /** 전략 선택 기준값 — 근거가 쌓이기 전의 임시값이라 설정으로 바꿀 수 있게 둔다. */
    @Bean
    StrategyPolicy strategyPolicy(
            @Value("${momentory.strategy.min-confidence:0.5}") double minConfidence,
            @Value("${momentory.strategy.close-confidence:0.8}") double closeConfidence,
            @Value("${momentory.strategy.resistance-threshold:2}") int resistanceThreshold,
            @Value("${momentory.strategy.question-cap:8}") int questionCap,
            @Value("${momentory.strategy.follow-up-limit:3}") int followUpLimit) {
        return new StrategyPolicy(minConfidence, closeConfidence, resistanceThreshold, questionCap, followUpLimit);
    }

    @Bean
    public NeedActionCatalog needActionCatalog(ObjectMapper objectMapper) throws IOException {
        try (var input = new ClassPathResource("retrospect/need-actions.json").getInputStream()) {
            return new NeedActionCatalog(objectMapper.readValue(input, NeedActionCatalog.Snapshot.class));
        }
    }
}
