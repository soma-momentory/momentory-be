package com.momentory.retrospect.infrastructure.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import io.micrometer.observation.ObservationRegistry;

@Configuration
@EnableConfigurationProperties(JevApiProperties.class)
public class JevApiClientConfiguration {

    @Bean
    RestClient jevRestClient(JevApiProperties properties, ObservationRegistry observationRegistry) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(GeminiApiClientConfiguration.requestFactory(
                        properties.connectTimeout(), properties.readTimeout()))
                .observationRegistry(observationRegistry)
                .build();
    }
}
