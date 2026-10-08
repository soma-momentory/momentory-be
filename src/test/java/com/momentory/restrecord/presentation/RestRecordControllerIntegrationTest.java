package com.momentory.restrecord.presentation;

import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.common.time.DayBoundary;
import com.momentory.restrecord.domain.RestMood;
import com.momentory.restrecord.infrastructure.RestRecordRepository;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers(disabledWithoutDocker = true)
class RestRecordControllerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"));

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired RestRecordRepository restRecordRepository;
    @Autowired AccessTokenIssuer accessTokenIssuer;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void cleanUp() {
        restRecordRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void recordsCompletedVisitWithMoodOnToday() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(postRecord(user, "{\"content\":\"WORRY_BOX\",\"completed\":true,\"mood\":\"BETTER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.content").value("WORRY_BOX"))
                .andExpect(jsonPath("$.date").value(DayBoundary.today().toString()))
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.mood").value("BETTER"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        assertThat(restRecordRepository.findAll())
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.isCompleted()).isTrue();
                    assertThat(saved.getMood()).isEqualTo(RestMood.BETTER);
                    assertThat(saved.getRecordDate()).isEqualTo(DayBoundary.today());
                });
    }

    @Test
    void recordsIncompleteVisitWithoutMood() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(postRecord(user, "{\"content\":\"SENSE_POND\",\"completed\":false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.mood").doesNotExist());
    }

    @Test
    void listsOnlyThatContentNewestFirstAndNeverAnotherUsers() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        User anotherUser = userRepository.saveAndFlush(User.create());
        mockMvc.perform(postRecord(user, "{\"content\":\"BODY_RELEASE\",\"completed\":true,\"mood\":\"SAME\"}")).andExpect(status().isCreated());
        mockMvc.perform(postRecord(user, "{\"content\":\"BODY_RELEASE\",\"completed\":true,\"mood\":\"UNSURE\"}")).andExpect(status().isCreated());
        mockMvc.perform(postRecord(user, "{\"content\":\"TREASURE_BOX\",\"completed\":true,\"mood\":\"BETTER\"}")).andExpect(status().isCreated());
        mockMvc.perform(postRecord(anotherUser, "{\"content\":\"BODY_RELEASE\",\"completed\":false}")).andExpect(status().isCreated());

        mockMvc.perform(getRecords(user).param("content", "BODY_RELEASE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(2))
                .andExpect(jsonPath("$.records[0].mood").value("UNSURE"))
                .andExpect(jsonPath("$.records[1].mood").value("SAME"));
        mockMvc.perform(getRecords(user).param("content", "WORRY_BOX"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(0));
    }

    @Test
    void rejectsMismatchedCompletedAndMoodWithoutSaving() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(postRecord(user, "{\"content\":\"WORRY_BOX\",\"completed\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("끝까지 했을 때는 기분을 골라주세요."));
        mockMvc.perform(postRecord(user, "{\"content\":\"WORRY_BOX\",\"completed\":false,\"mood\":\"BETTER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("끝까지 하지 않았을 때는 기분을 남길 수 없습니다."));
        mockMvc.perform(postRecord(user, "{\"completed\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("쉼터를 입력해주세요."));
        mockMvc.perform(postRecord(user, "{\"content\":\"WORRY_BOX\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("끝까지 했는지 입력해주세요."));
        mockMvc.perform(postRecord(user, "{\"content\":\"NOT_A_SHELTER\",\"completed\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다."));

        assertThat(restRecordRepository.count()).isZero();
    }

    @Test
    void rejectsMissingOrInvalidContentOnList() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(getRecords(user))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다."));
        mockMvc.perform(getRecords(user).param("content", "NOT_A_SHELTER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다."));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/rest-records").param("content", "WORRY_BOX"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(post("/api/v1/rest-records").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"WORRY_BOX\",\"completed\":false}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        assertThat(restRecordRepository.count()).isZero();
    }

    @Test
    void removesRecordsWhenUserIsDeleted() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        mockMvc.perform(postRecord(user, "{\"content\":\"WORRY_BOX\",\"completed\":false}")).andExpect(status().isCreated());

        userRepository.deleteById(user.getId());
        userRepository.flush();

        assertThat(restRecordRepository.count()).isZero();
    }

    private MockHttpServletRequestBuilder postRecord(User user, String body) {
        return post("/api/v1/rest-records")
                .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder getRecords(User user) {
        return get("/api/v1/rest-records").header(HttpHeaders.AUTHORIZATION, bearerToken(user));
    }

    private String bearerToken(User user) {
        return "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole());
    }
}
