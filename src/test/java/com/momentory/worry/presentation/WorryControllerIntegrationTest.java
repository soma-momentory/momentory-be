package com.momentory.worry.presentation;

import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.common.time.DayBoundary;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;
import com.momentory.worry.domain.Worry;
import com.momentory.worry.infrastructure.WorryRepository;
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

import java.time.LocalDate;

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
class WorryControllerIntegrationTest {

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
    @Autowired WorryRepository worryRepository;
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
        worryRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void keepsWorryOnTodayAndStripsContent() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(postWorry(user, "  내일 발표가 걱정돼요  "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.date").value(DayBoundary.today().toString()))
                .andExpect(jsonPath("$.content").value("내일 발표가 걱정돼요"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        assertThat(worryRepository.findAll())
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getContent()).isEqualTo("내일 발표가 걱정돼요");
                    assertThat(saved.getWorryDate()).isEqualTo(DayBoundary.today());
                });
    }

    @Test
    void listsAllWorriesNewestFirst() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        LocalDate yesterday = DayBoundary.today().minusDays(1);
        worryRepository.saveAndFlush(Worry.create(user.getId(), yesterday, "어제의 걱정"));
        mockMvc.perform(postWorry(user, "오늘의 걱정")).andExpect(status().isCreated());

        mockMvc.perform(getWorries(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.worries.length()").value(2))
                .andExpect(jsonPath("$.worries[0].content").value("오늘의 걱정"))
                .andExpect(jsonPath("$.worries[1].content").value("어제의 걱정"))
                .andExpect(jsonPath("$.worries[1].date").value(yesterday.toString()));
    }

    @Test
    void neverShowsAnotherUsersWorries() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        User anotherUser = userRepository.saveAndFlush(User.create());
        mockMvc.perform(postWorry(anotherUser, "다른 사람의 걱정")).andExpect(status().isCreated());

        mockMvc.perform(getWorries(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.worries.length()").value(0));
    }

    @Test
    void rejectsBlankOrTooLongContentWithoutSaving() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        mockMvc.perform(postWorry(user, "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("걱정 내용을 입력해주세요."));
        mockMvc.perform(postWorry(user, "가".repeat(Worry.CONTENT_MAX_LENGTH + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("걱정 내용은 최대 100자입니다."));
        mockMvc.perform(postWorry(user, "가".repeat(Worry.CONTENT_MAX_LENGTH)))
                .andExpect(status().isCreated());

        assertThat(worryRepository.count()).isEqualTo(1);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/worries"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(post("/api/v1/worries").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"걱정\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        assertThat(worryRepository.count()).isZero();
    }

    @Test
    void removesWorriesWhenUserIsDeleted() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        mockMvc.perform(postWorry(user, "걱정")).andExpect(status().isCreated());

        userRepository.deleteById(user.getId());
        userRepository.flush();

        assertThat(worryRepository.count()).isZero();
    }

    private MockHttpServletRequestBuilder postWorry(User user, String content) {
        return post("/api/v1/worries")
                .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"%s\"}".formatted(content));
    }

    private MockHttpServletRequestBuilder getWorries(User user) {
        return get("/api/v1/worries").header(HttpHeaders.AUTHORIZATION, bearerToken(user));
    }

    private String bearerToken(User user) {
        return "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole());
    }
}
