package com.momentory.retrospect.infrastructure;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import com.momentory.retrospect.domain.NeedActionCatalog;
import com.momentory.retrospect.domain.safety.SafetyPolicy;

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

    @Bean
    public NeedActionCatalog needActionCatalog(ObjectMapper objectMapper) throws IOException {
        try (var input = new ClassPathResource("retrospect/need-actions.json").getInputStream()) {
            return new NeedActionCatalog(objectMapper.readValue(input, NeedActionCatalog.Snapshot.class));
        }
    }
}
