package com.momentory.notification.presentation;

import com.momentory.MomentoryApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = MomentoryApplication.class, properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers(disabledWithoutDocker = true)
class NotificationOpenApiContractIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"));

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void documentsResponsesAndExactRequestFields() throws Exception {
        JsonNode docs = mapper.readTree(mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertSchema(docs, "/api/v1/notifications", "get", "200", "NotificationListResponse");
        assertError(docs, "/api/v1/notifications", "get", "400", "INVALID_CURSOR", "INVALID_CURSOR", "알림 목록 커서가 올바르지 않습니다.");
        assertError(docs, "/api/v1/notifications", "get", "400", "INVALID_REQUEST", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertError(docs, "/api/v1/notifications", "get", "401", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertSchema(docs, "/api/v1/notifications/unread", "get", "200", "UnreadResponse");
        assertError(docs, "/api/v1/notifications/unread", "get", "401", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertNoContent(docs, "/api/v1/notifications/{id}/read", "patch");
        assertError(docs, "/api/v1/notifications/{id}/read", "patch", "401", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
        assertError(docs, "/api/v1/notifications/{id}/read", "patch", "404", "NOTIFICATION_NOT_FOUND", "NOTIFICATION_NOT_FOUND", "알림을 찾을 수 없습니다.");
        assertNoContent(docs, "/api/v1/notifications/read", "post");
        assertError(docs, "/api/v1/notifications/read", "post", "400", "INVALID_REQUEST", "INVALID_REQUEST", "잘못된 요청입니다.");
        assertError(docs, "/api/v1/notifications/read", "post", "401", "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");

        JsonNode item = docs.at("/components/schemas/NotificationResponse/properties");
        assertThat(item.size()).isEqualTo(7);
        assertThat(item.path("id").path("type").stringValue()).isEqualTo("string");
        assertThat(item.has("type")).isTrue();
        assertThat(item.has("title")).isTrue();
        assertThat(item.has("body")).isTrue();
        assertThat(item.has("createdAt")).isTrue();
        assertThat(item.has("readAt")).isTrue();
        assertThat(item.has("link")).isTrue();
        JsonNode request = docs.at("/components/schemas/ReadNotificationsRequest/properties");
        assertThat(request.size()).isEqualTo(1);
        assertThat(request.has("ids")).isTrue();
        assertThat(docs.at("/components/schemas/UnreadResponse/properties").size()).isEqualTo(1);
    }

    private void assertSchema(JsonNode docs, String path, String method, String status, String schema) {
        assertThat(response(docs, path, method, status)
                .at("/content/application~1json/schema/$ref").stringValue())
                .isEqualTo("#/components/schemas/" + schema);
    }

    private void assertError(JsonNode docs, String path, String method, String status,
                             String example, String code, String message) {
        assertSchema(docs, path, method, status, "ApiErrorResponse");
        JsonNode value = response(docs, path, method, status)
                .at("/content/application~1json/examples/" + example + "/value");
        assertThat(value.path("code").stringValue()).isEqualTo(code);
        assertThat(value.path("message").stringValue()).isEqualTo(message);
    }

    private void assertNoContent(JsonNode docs, String path, String method) {
        assertThat(response(docs, path, method, "204").has("content")).isFalse();
    }

    private JsonNode response(JsonNode docs, String path, String method, String status) {
        return docs.at("/paths/" + path.replace("~", "~0").replace("/", "~1")
                + "/" + method + "/responses/" + status);
    }
}
