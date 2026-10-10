package com.momentory.config;

import com.momentory.auth.kakao.infrastructure.KakaoApiClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml 의 메트릭 설정이 실제 컨텍스트에서 의도대로 묶이는지 본다 — OTLP 전송은 별도 스레드에서
 * 실패를 경고로만 남겨서, 설정이 틀어져도 다른 테스트로는 드러나지 않는다.
 */
@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers(disabledWithoutDocker = true)
class MetricsIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"));

    static final MockWebServer KAKAO = new MockWebServer();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("kakao.api.base-url", () -> KAKAO.url("/").toString());
    }

    @AfterAll
    static void stopKakao() throws IOException {
        KAKAO.shutdown();
    }

    @Autowired ApplicationContext context;
    @Autowired MeterRegistry meterRegistry;
    @Autowired KakaoApiClient kakaoApiClient;

    @Test
    void doesNotExportToOtlpUnlessEnabled() {
        // 운영 작업 정의만 GRAFANA_OTLP_ENABLED=true 를 준다. 꺼져 있어야 로컬 · dev 가 localhost:4318 로 보내지 않는다.
        assertThat(context.getBeansOfType(MeterRegistry.class).values())
                .noneMatch(registry -> registry.getClass().getSimpleName().equals("OtlpMeterRegistry"));
    }

    @Test
    void recordsExternalCallWithUriTemplateAndPercentiles() {
        KAKAO.enqueue(json("""
                {"id":1001,"app_id":123456789,"expires_in":3600}
                """));
        KAKAO.enqueue(json("""
                {"id":1001,"kakao_account":{"email_needs_agreement":false,"is_email_valid":true,
                  "is_email_verified":true,"email":"user@example.com"}}
                """));

        kakaoApiClient.getUserInfo("kakao-access-token");

        Timer timer = meterRegistry.get("http.client.requests").tag("uri", "/v2/user/me").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(Arrays.stream(timer.takeSnapshot().percentileValues()).map(ValueAtPercentile::percentile))
                .containsExactly(0.5, 0.95, 0.99);
    }

    private static MockResponse json(String body) {
        return new MockResponse().addHeader("Content-Type", "application/json").setBody(body);
    }
}
