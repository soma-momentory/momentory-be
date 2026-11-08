package com.momentory.letter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.momentory.actioncard.domain.ActionCard;
import com.momentory.actioncard.infrastructure.persistence.ActionCardRepository;
import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.common.time.DayBoundary;
import com.momentory.diary.domain.Diary;
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.letter.application.LetterGenerator;
import com.momentory.letter.application.LetterService;
import com.momentory.letter.domain.Letter;
import com.momentory.letter.domain.LetterAction;
import com.momentory.letter.domain.LetterMaterials;
import com.momentory.letter.infrastructure.LetterActionRepository;
import com.momentory.letter.infrastructure.LetterRepository;
import com.momentory.letter.infrastructure.ai.GeminiLetterWriter;
import com.momentory.letter.infrastructure.ai.GeminiLetterWriter.Body;
import com.momentory.retrospect.application.ReplyDto;
import com.momentory.retrospect.application.RetrospectResult;
import com.momentory.retrospect.application.RetrospectService;
import com.momentory.retrospect.application.StartCommand;
import com.momentory.retrospect.application.TurnCommand;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.RetrospectStatus;
import com.momentory.retrospect.infrastructure.persistence.Retrospect;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.retrospect.infrastructure.persistence.RetrospectStateCodec;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;

/**
 * 편지 → 밤 회고 → 바람카드 — 바바의 편지 PRD 4-5 · 14-3 · 14-4. 편지 생성의 Gemini 는 흉내 내고,
 * 회고의 「해 봤어요?」 단계는 AI 를 부르지 않는다.
 */
@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers(disabledWithoutDocker = true)
class LetterRetrospectLinkIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"));

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean GeminiLetterWriter writer;

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired RetrospectRepository retrospectRepository;
    @Autowired DiaryRepository diaryRepository;
    @Autowired ActionCardRepository actionCardRepository;
    @Autowired LetterRepository letterRepository;
    @Autowired LetterActionRepository letterActionRepository;
    @Autowired RetrospectStateCodec codec;
    @Autowired LetterGenerator generator;
    @Autowired LetterService letterService;
    @Autowired RetrospectService retrospectService;
    @Autowired AccessTokenIssuer accessTokenIssuer;
    @Autowired JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        when(writer.writeBody(any())).thenReturn(Optional.of(new Body("", "관점", "오늘 밤엔 발표부터 물어볼게요.")));
    }

    @AfterEach
    void cleanUp() {
        letterRepository.deleteAllInBatch();
        actionCardRepository.deleteAllInBatch();
        diaryRepository.deleteAllInBatch();
        retrospectRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void choosingInLetterFillsWishCardAndNightRetrospectAsksThenChecksCard() {
        User user = userRepository.saveAndFlush(User.create());
        Seeded seeded = letterWithCard(user, yesterday());

        letterService.chooseAction(user.getId(), seeded.letter().getId(), 1, "점심 뒤", null, null);
        String title = chosen(seeded.letter()).getTitle();
        assertThat(card(seeded).getTargetAction()).isEqualTo(title);

        RetrospectResult started = retrospectService.start(user.getId(), StartCommand.today("정민", null));
        assertThat(started.reply().text()).isEqualTo("정민님, 오늘 「" + title + "」 해 봤어요?");
        assertThat(started.reply().options()).extracting(ReplyDto.OptionDto::label)
                .containsExactly("해 봤어요", "못 했어요", "다른 얘기 할래요");

        ReplyDto answered = retrospectService.handle(user.getId(), started.id(), TurnCommand.option("1"));

        assertThat(answered.text()).isEqualTo("해 봤군요, 잘했어요. 정민님, 오늘 하루 중에서 가장 기억에 남는 일은 뭐였어요?");
        LetterAction action = chosen(seeded.letter());
        assertThat(action.getResult()).isEqualTo("DONE");
        assertThat(action.getResultAt()).isNotNull();
        ActionCard checked = card(seeded);
        assertThat(checked.isDone()).isTrue();
        assertThat(checked.getDoneAt()).isNotNull();
    }

    @Test
    void notTriedAnswerKeepsCardUnchecked() {
        User user = userRepository.saveAndFlush(User.create());
        Seeded seeded = letterWithCard(user, yesterday());
        letterService.chooseAction(user.getId(), seeded.letter().getId(), 0, null, null, null);

        RetrospectResult started = retrospectService.start(user.getId(), StartCommand.today("정민", null));
        retrospectService.handle(user.getId(), started.id(), TurnCommand.option("2"));

        assertThat(chosen(seeded.letter()).getResult()).isEqualTo("NOT_DONE");
        assertThat(card(seeded).isDone()).isFalse();
    }

    @Test
    void checkedInShelterMakesNightRetrospectAskHowItWent() {
        User user = userRepository.saveAndFlush(User.create());
        Seeded seeded = letterWithCard(user, yesterday());
        letterService.chooseAction(user.getId(), seeded.letter().getId(), 2, null, null, null);
        ActionCard shelterChecked = card(seeded);
        shelterChecked.changeCompletion(true, "생각보다 개운했다", java.time.Instant.now());
        actionCardRepository.saveAndFlush(shelterChecked);
        String title = chosen(seeded.letter()).getTitle();

        RetrospectResult started = retrospectService.start(user.getId(), StartCommand.today("정민", null));

        assertThat(started.reply().text()).isEqualTo("오늘 「" + title + "」 해 봤네요. 해 보니 어땠어요?");
        retrospectService.handle(user.getId(), started.id(), TurnCommand.option("1"));
        assertThat(chosen(seeded.letter()).getResult()).isEqualTo("DONE");
        assertThat(card(seeded).getReflection()).isEqualTo("생각보다 개운했다");
    }

    @Test
    void unchoosingClearsCardAndTriedChoiceIsLocked() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        Seeded seeded = letterWithCard(user, yesterday());
        String path = "/api/v1/letters/" + seeded.letter().getId() + "/action";

        letterService.chooseAction(user.getId(), seeded.letter().getId(), 1, null, null, null);
        letterService.chooseAction(user.getId(), seeded.letter().getId(), null, null, null, null);
        assertThat(card(seeded).getTargetAction()).isNull();

        letterService.chooseAction(user.getId(), seeded.letter().getId(), 0, null, null, null);
        RetrospectResult started = retrospectService.start(user.getId(), StartCommand.today("정민", null));
        retrospectService.handle(user.getId(), started.id(), TurnCommand.option("1"));

        mockMvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"position\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LETTER_ACTION_LOCKED"))
                .andExpect(jsonPath("$.message").value("이미 해 본 작은 일은 바꿀 수 없습니다."));
        assertThat(chosen(seeded.letter()).getPosition()).isZero();
        assertThat(card(seeded).getTargetAction()).isEqualTo(chosen(seeded.letter()).getTitle());
    }

    @Test
    void noChosenActionKeepsOriginalOpening() {
        User user = userRepository.saveAndFlush(User.create());
        letterWithCard(user, yesterday());

        RetrospectResult started = retrospectService.start(user.getId(), StartCommand.today("정민", null));

        assertThat(started.reply().text()).isEqualTo("정민님, 오늘 하루 중에서 가장 기억에 남는 일은 뭐였어요?");
        assertThat(started.reply().options()).isNull();
    }

    @Test
    void nextLetterSeesWhetherYesterdaysActionWasTried() {
        User user = userRepository.saveAndFlush(User.create());
        Seeded before = letterWithCard(user, yesterday().minusDays(1));
        letterService.chooseAction(user.getId(), before.letter().getId(), 0, null, null, null);
        String title = chosen(before.letter()).getTitle();
        ActionCard checked = card(before);
        checked.changeCompletion(true, null, java.time.Instant.now());
        actionCardRepository.saveAndFlush(checked);
        Seeded today = letterWithCard(user, yesterday());

        generator.generateForDiary(today.diary().getId());

        ArgumentCaptor<LetterMaterials> materials = ArgumentCaptor.forClass(LetterMaterials.class);
        verify(writer, org.mockito.Mockito.atLeastOnce()).writeBody(materials.capture());
        assertThat(materials.getValue().previousAction()).isEqualTo(title + " (해 봤다고 함)");
    }

    @Test
    void cardAlreadyCheckedInShelterLocksEvenTheFirstChoice() {
        User user = userRepository.saveAndFlush(User.create());
        Seeded seeded = letterWithCard(user, yesterday());
        ActionCard checked = card(seeded);
        checked.changeCompletion(true, null, java.time.Instant.now());
        actionCardRepository.saveAndFlush(checked);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        letterService.chooseAction(user.getId(), seeded.letter().getId(), 1, null, null, null))
                .isInstanceOf(com.momentory.letter.application.LetterActionLockedException.class);

        assertThat(letterActionRepository.findByLetterIdAndChosenTrue(seeded.letter().getId())).isEmpty();
        assertThat(card(seeded).getTargetAction()).isNull();
    }

    @Test
    void answerIsIgnoredWhenChoiceChangedDuringRetrospect() {
        User user = userRepository.saveAndFlush(User.create());
        Seeded seeded = letterWithCard(user, yesterday());
        letterService.chooseAction(user.getId(), seeded.letter().getId(), 0, null, null, null);
        RetrospectResult started = retrospectService.start(user.getId(), StartCommand.today("정민", null));
        // 「해 봤어요?」가 떠 있는 동안 편지에서 다른 일로 바꿨다.
        letterService.chooseAction(user.getId(), seeded.letter().getId(), 2, null, null, null);

        retrospectService.handle(user.getId(), started.id(), TurnCommand.option("1"));

        assertThat(letterActionRepository.findByLetterIdOrderByPositionAsc(seeded.letter().getId()))
                .extracting(LetterAction::getResult).containsOnlyNulls();
        assertThat(card(seeded).isDone()).isFalse();
    }

    @Test
    void nextLetterTreatsCardAsTruthAndMentionsNotDoneGently() {
        User user = userRepository.saveAndFlush(User.create());
        Seeded notDone = letterWithCard(user, yesterday().minusDays(2));
        letterService.chooseAction(user.getId(), notDone.letter().getId(), 0, null, null, null);
        LetterAction notDoneAction = chosen(notDone.letter());
        notDoneAction.recordResult("NOT_DONE", java.time.Instant.now());
        letterActionRepository.saveAndFlush(notDoneAction);
        Seeded cancelled = letterWithCard(user, yesterday().minusDays(1));
        letterService.chooseAction(user.getId(), cancelled.letter().getId(), 0, null, null, null);
        // 밤에는 「해 봤어요」였지만 쉼터에서 취소했다 — 카드가 원본이다.
        LetterAction cancelledAction = chosen(cancelled.letter());
        cancelledAction.recordResult("DONE", java.time.Instant.now());
        letterActionRepository.saveAndFlush(cancelledAction);
        Seeded today = letterWithCard(user, yesterday());

        ArgumentCaptor<LetterMaterials> materials = ArgumentCaptor.forClass(LetterMaterials.class);
        verify(writer, org.mockito.Mockito.atLeastOnce()).writeBody(materials.capture());
        List<String> previous = materials.getAllValues().stream().map(LetterMaterials::previousAction).toList();

        // 차례대로 notDone · cancelled · today 의 편지를 만들 때 넘긴 재료다.
        assertThat(previous.get(1)).isEqualTo(notDoneAction.getTitle() + " (못 했다고 함 · 못 해도 괜찮다는 쪽으로)");
        assertThat(previous.get(2)).isNull();
        assertThat(today.letter()).isNotNull();
    }

    // ── 도우미 ───────────────────────────────────────────────────────────

    private record Seeded(Diary diary, Letter letter, Long retrospectId) {
    }

    /** 그 날짜의 회고 · 일기 · 바람카드(작은 행동 없음) · 편지(만들어 둠). */
    private Seeded letterWithCard(User user, LocalDate date) {
        RetrospectState state = new RetrospectState(UUID.randomUUID().toString());
        state.addUserMessage("발표 전에 손이 떨렸어요");
        Retrospect retrospect = retrospectRepository.save(Retrospect.start(user.getId(),
                RetrospectStatus.COMPLETED, null, codec.serialize(state)));
        Diary diary = diaryRepository.save(Diary.create(user.getId(), retrospect.getId(), "발표가 있었다.", null, null));
        // 그날 밤 10시(KST)에 남긴 일기 — 오늘 회고를 막지 않게 날짜를 맞춘다.
        jdbc.update("update diaries set created_at = ?::date + interval '13 hours' where id = ?",
                date.toString(), diary.getId());
        actionCardRepository.save(ActionCard.createWish(user.getId(), retrospect.getId(), "발표", null, date,
                null, "휴식", null, null));
        Letter letter = letterRepository.save(Letter.pending(user.getId(), diary.getId(), date));
        generator.generateForDiary(diary.getId());
        return new Seeded(diary, letter, retrospect.getId());
    }

    private LetterAction chosen(Letter letter) {
        return letterActionRepository.findByLetterIdAndChosenTrue(letter.getId()).orElseThrow();
    }

    private ActionCard card(Seeded seeded) {
        return actionCardRepository.findByRetrospectId(seeded.retrospectId()).orElseThrow();
    }

    private String token(User user) {
        return accessTokenIssuer.issueAccessToken(user.getId(), user.getRole());
    }

    private static LocalDate yesterday() {
        return DayBoundary.today().minusDays(1);
    }
}
