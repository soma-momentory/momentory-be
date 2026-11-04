package com.momentory.wish;

import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.common.time.DayBoundary;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;
import com.momentory.wish.application.WishReviewAi;
import com.momentory.wish.domain.Wish;
import com.momentory.wish.domain.WishRuleAgreement;
import com.momentory.wish.infrastructure.WishJoinRepository;
import com.momentory.wish.infrastructure.WishReportRepository;
import com.momentory.wish.infrastructure.WishRepository;
import com.momentory.wish.infrastructure.WishRuleAgreementRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers(disabledWithoutDocker = true)
class WishApiIntegrationTest {

    private static final String BASE = "/api/v1/wishes";

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
    @Autowired WishRepository wishRepository;
    @Autowired WishJoinRepository joinRepository;
    @Autowired WishReportRepository reportRepository;
    @Autowired WishRuleAgreementRepository agreementRepository;
    @Autowired AccessTokenIssuer accessTokenIssuer;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean WishReviewAi reviewAi;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @AfterEach
    void cleanUp() {
        reportRepository.deleteAllInBatch();
        joinRepository.deleteAllInBatch();
        wishRepository.deleteAllInBatch();
        agreementRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void reviewThenPostFloatsWishAndCountsTodayOnce() throws Exception {
        User user = agreed();
        when(reviewAi.review(anyString())).thenReturn(Optional.of(new WishReviewAi.Verdict(
                WishReviewAi.Decision.PASS, "그 사람이 답장해 주면 좋겠어요", true)));

        var reviewed = perform(user, review("김지훈이 답장해 주면 좋겠어요"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("EDITED"))
                .andExpect(jsonPath("$.content").value("그 사람이 답장해 주면 좋겠어요"))
                .andReturn();
        String token = objectMapper.readTree(reviewed.getResponse().getContentAsString()).get("reviewToken").asString();
        assertThat(wishRepository.count()).isZero();

        perform(user, postWish("그 사람이 답장해 주면 좋겠어요", token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.daysLeft").value(7));

        perform(user, postWish("그 사람이 답장해 주면 좋겠어요", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WISH_DAILY_LIMIT"));
        perform(user, review("또 빌래요"))
                .andExpect(status().isConflict());
        perform(user, get(BASE + "/pond"))
                .andExpect(jsonPath("$.postedToday").value(true))
                .andExpect(jsonPath("$.hasFloatingWish").value(true))
                .andExpect(jsonPath("$.rulesAgreed").value(true));
    }

    @Test
    void cannotPostTextThatWasNotReviewed() throws Exception {
        User user = agreed();
        when(reviewAi.review(anyString())).thenReturn(Optional.of(new WishReviewAi.Verdict(
                WishReviewAi.Decision.PASS, "좋은 소원이에요", true)));
        var reviewed = perform(user, review("좋은 소원이에요")).andReturn();
        String token = objectMapper.readTree(reviewed.getResponse().getContentAsString()).get("reviewToken").asString();

        perform(user, postWish("표와 다른 글이에요", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WISH_REVIEW_INVALID"));
        perform(agreed(), postWish("좋은 소원이에요", token))
                .andExpect(status().isBadRequest());
        assertThat(wishRepository.count()).isZero();
    }

    @Test
    void requiresRulesBeforeWriting() throws Exception {
        User user = userRepository.saveAndFlush(User.create());

        perform(user, get(BASE + "/pond")).andExpect(jsonPath("$.rulesAgreed").value(false));
        perform(user, review("소원이에요"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WISH_RULES_REQUIRED"));

        perform(user, post(BASE + "/rules-agreement")).andExpect(status().isNoContent());
        perform(user, get(BASE + "/pond")).andExpect(jsonPath("$.rulesAgreed").value(true));
    }

    @Test
    void crisisComesFirstAndNeverCallsAi() throws Exception {
        perform(agreed(), review("죽고 싶어요"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("CRISIS"))
                .andExpect(jsonPath("$.reviewToken").doesNotExist());
        verify(reviewAi, never()).review(anyString());
        assertThat(wishRepository.count()).isZero();
    }

    @Test
    void mapsAiDecisionsAndFailsClosed() throws Exception {
        User user = agreed();
        when(reviewAi.review("은근한 위기")).thenReturn(verdict(WishReviewAi.Decision.CRISIS));
        when(reviewAi.review("그 사람이 잘리면 좋겠어요")).thenReturn(verdict(WishReviewAi.Decision.HARM));
        when(reviewAi.review("안 되는 날")).thenReturn(Optional.empty());

        perform(user, review("은근한 위기")).andExpect(jsonPath("$.result").value("CRISIS"));
        perform(user, review("그 사람이 잘리면 좋겠어요"))
                .andExpect(jsonPath("$.result").value("REJECTED"))
                .andExpect(jsonPath("$.message").value("누군가 잘못되길 바라는 말은 띄울 수 없어요"));
        perform(user, review("안 되는 날"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("WISH_REVIEW_UNAVAILABLE"));
    }

    @Test
    void stripsContactsTheAiMissed() throws Exception {
        when(reviewAi.review(anyString())).thenReturn(Optional.of(new WishReviewAi.Verdict(
                WishReviewAi.Decision.PASS, "연락 주면 좋겠어요 010-1234-5678", true)));

        perform(agreed(), review("연락 주면 좋겠어요 010-1234-5678"))
                .andExpect(jsonPath("$.result").value("EDITED"))
                .andExpect(jsonPath("$.content").value("연락 주면 좋겠어요"));
    }

    @Test
    void pondHidesMineReportedAndFillsWithBaba() throws Exception {
        User me = agreed();
        User other = user();
        User reported = user();
        floating(me, "내 소원");
        Wish visible = floating(other, "보이는 소원");
        Wish hidden = floating(reported, "신고한 사람 소원");

        perform(me, post(BASE + "/" + hidden.getId() + "/report")).andExpect(status().isNoContent());

        perform(me, get(BASE + "/pond"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wishes.length()").value(5))
                .andExpect(jsonPath("$.wishes[0].id").value(visible.getId()))
                .andExpect(jsonPath("$.wishes[0].author").value("USER"))
                .andExpect(jsonPath("$.wishes[0].daysLeft").value(7))
                .andExpect(jsonPath("$.wishes[1].author").value("BABA"))
                .andExpect(jsonPath("$.wishes[1].id").value(org.hamcrest.Matchers.lessThan(0)));

        perform(me, delete(BASE + "/" + hidden.getId() + "/report")).andExpect(status().isNoContent());
        perform(me, get(BASE + "/pond")).andExpect(jsonPath("$.wishes[1].author").value("USER"));
    }

    @Test
    void showsOneWishPerAuthorAndHidesExpired() throws Exception {
        User me = agreed();
        User other = user();
        floating(other, "오늘 소원");
        wishRepository.saveAndFlush(Wish.create(other.getId(), DayBoundary.today().minusDays(1), "어제 소원", true,
                Instant.now()));
        wishRepository.saveAndFlush(Wish.create(user().getId(), DayBoundary.today(), "끝난 소원", true,
                Instant.now().minus(Duration.ofDays(8))));

        perform(me, get(BASE + "/pond"))
                .andExpect(jsonPath("$.wishes[?(@.author == 'USER')]", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void joinIsOnceAndAuthorSeesItWithoutCount() throws Exception {
        User author = agreed();
        User other = user();
        Wish wish = floating(author, "같이 빌어 주세요");

        perform(author, get(BASE + "/mine")).andExpect(jsonPath("$.floating[0].joinedByOthers").value(false));
        perform(other, post(BASE + "/" + wish.getId() + "/join")).andExpect(status().isNoContent());
        perform(other, post(BASE + "/" + wish.getId() + "/join")).andExpect(status().isNoContent());
        assertThat(joinRepository.count()).isOne();

        perform(other, get(BASE + "/pond")).andExpect(jsonPath("$.wishes[0].joined").value(true));
        perform(author, get(BASE + "/mine")).andExpect(jsonPath("$.floating[0].joinedByOthers").value(true));
        perform(author, post(BASE + "/" + wish.getId() + "/join")).andExpect(status().isNotFound());
    }

    @Test
    void threeDifferentReportersTakeWishDown() throws Exception {
        User author = agreed();
        Wish wish = floating(author, "신고될 소원");
        User first = user();

        perform(first, post(BASE + "/" + wish.getId() + "/report")).andExpect(status().isNoContent());
        perform(first, post(BASE + "/" + wish.getId() + "/report")).andExpect(status().isNoContent());
        perform(user(), post(BASE + "/" + wish.getId() + "/report")).andExpect(status().isNoContent());
        perform(user(), get(BASE + "/pond")).andExpect(jsonPath("$.wishes[0].author").value("USER"));

        perform(user(), post(BASE + "/" + wish.getId() + "/report")).andExpect(status().isNoContent());

        perform(user(), get(BASE + "/pond")).andExpect(jsonPath("$.wishes[0].author").value("BABA"));
        perform(author, get(BASE + "/mine"))
                .andExpect(jsonPath("$.past[0].endReason").value("REPORTED"));
    }

    @Test
    void deleteClearsTextAndKeepsTodaysTurn() throws Exception {
        User author = agreed();
        Wish wish = floating(author, "지울 소원");

        perform(user(), delete(BASE + "/" + wish.getId())).andExpect(status().isNotFound());
        perform(author, delete(BASE + "/" + wish.getId())).andExpect(status().isNoContent());

        assertThat(wishRepository.findById(wish.getId()).orElseThrow().getContent()).isEmpty();
        perform(author, get(BASE + "/mine")).andExpect(jsonPath("$.floating.length()").value(0));
        perform(author, review("다시 빌래요")).andExpect(status().isConflict());
    }

    @Test
    void answersPastWish() throws Exception {
        User author = agreed();
        Wish past = wishRepository.saveAndFlush(Wish.create(author.getId(), DayBoundary.today(), "지난 소원", true,
                Instant.now().minus(Duration.ofDays(8))));

        perform(author, get(BASE + "/mine")).andExpect(jsonPath("$.past[0].endReason").value("EXPIRED"));
        perform(author, put(BASE + "/" + past.getId() + "/outcome").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"CAME_TRUE\"}")).andExpect(status().isNoContent());
        perform(author, get(BASE + "/mine")).andExpect(jsonPath("$.past[0].outcome").value("CAME_TRUE"));
    }

    @Test
    void validatesLengthAndAuthentication() throws Exception {
        perform(agreed(), post(BASE + "/review").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("content", "가".repeat(121)))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE + "/pond")).andExpect(status().isUnauthorized());
    }

    private Optional<WishReviewAi.Verdict> verdict(WishReviewAi.Decision decision) {
        return Optional.of(new WishReviewAi.Verdict(decision, "", false));
    }

    private User user() {
        return userRepository.saveAndFlush(User.create());
    }

    private User agreed() {
        User user = user();
        agreementRepository.saveAndFlush(new WishRuleAgreement(user.getId(), WishRuleAgreement.CURRENT_VERSION,
                Instant.now()));
        return user;
    }

    private Wish floating(User user, String content) {
        return wishRepository.saveAndFlush(Wish.create(user.getId(), DayBoundary.today(), content, true, Instant.now()));
    }

    private MockHttpServletRequestBuilder review(String content) throws Exception {
        return post(BASE + "/review").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("content", content)));
    }

    private MockHttpServletRequestBuilder postWish(String content, String token) throws Exception {
        return post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("content", content, "reviewToken", token)));
    }

    private org.springframework.test.web.servlet.ResultActions perform(User user, MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole())));
    }
}
