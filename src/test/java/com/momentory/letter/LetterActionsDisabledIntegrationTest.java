package com.momentory.letter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.momentory.actioncard.domain.ActionCard;
import com.momentory.actioncard.infrastructure.persistence.ActionCardRepository;
import com.momentory.common.time.DayBoundary;
import com.momentory.diary.domain.Diary;
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.letter.application.LetterActionNotFoundException;
import com.momentory.letter.application.LetterGenerator;
import com.momentory.letter.application.LetterService;
import com.momentory.letter.domain.Letter;
import com.momentory.letter.domain.LetterAction;
import com.momentory.letter.domain.LetterActionSource;
import com.momentory.letter.domain.LetterMaterials;
import com.momentory.letter.infrastructure.LetterActionRepository;
import com.momentory.letter.infrastructure.LetterRepository;
import com.momentory.letter.infrastructure.ai.GeminiLetterWriter;
import com.momentory.letter.infrastructure.ai.GeminiLetterWriter.Body;
import com.momentory.retrospect.application.RetrospectResult;
import com.momentory.retrospect.application.RetrospectService;
import com.momentory.retrospect.application.StartCommand;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.RetrospectStatus;
import com.momentory.retrospect.domain.assistant.DiaryChatAssistant;
import com.momentory.retrospect.infrastructure.persistence.Retrospect;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.retrospect.infrastructure.persistence.RetrospectStateCodec;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;

/**
 * 작은 일을 끈 서버(2026-11-17 팀 결정) — 새 편지에 작은 일을 만들지 않고, 지난 편지에 저장된 칸은 지우지 않은 채
 * 응답 · 고르기 · 밤 회고의 「해 봤어요?」 · 다음 편지 재료에서 모두 빠진다.
 */
@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789",
        "momentory.letter.actions-enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class LetterActionsDisabledIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"));

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean GeminiLetterWriter writer;
    @MockitoBean DiaryChatAssistant diaryChatAssistant;

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
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        when(writer.writeBody(any())).thenReturn(Optional.of(new Body("", "관점", "오늘 밤엔 발표부터 물어볼게요.")));
        when(writer.writeBodyOnOpen(any())).thenReturn(Optional.of(new Body("", "관점", "오늘 밤엔 발표부터 물어볼게요.")));
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
    void newLetterHasNoActionsAndDoesNotAskAiToPickThem() {
        User user = userRepository.saveAndFlush(User.create());
        Letter letter = seed(user, yesterday(), true);

        assertThat(letterActionRepository.findByLetterIdOrderByPositionAsc(letter.getId())).isEmpty();
        assertThat(letterService.byDate(user.getId(), yesterday()).actions()).isEmpty();
        assertThat(letterRepository.findById(letter.getId()).orElseThrow().getStatus().isDone()).isTrue();
        verify(writer, never()).pickNeeds(any());
        verify(writer, never()).pickActionIds(any(), any());
    }

    @Test
    void letterMadeOnOpenHasNoActionsEither() {
        User user = userRepository.saveAndFlush(User.create());
        seed(user, yesterday(), false);

        assertThat(letterService.byDate(user.getId(), yesterday()).actions()).isEmpty();
        verify(writer, atLeastOnce()).writeBodyOnOpen(any());
        verify(writer, never()).pickNeedsOnOpen(any());
        verify(writer, never()).pickActionIdsOnOpen(any(), any());
    }

    @Test
    void storedChosenActionStaysInDbButIsHiddenLockedAndNotAskedAtNight() {
        User user = userRepository.saveAndFlush(User.create());
        Letter letter = seed(user, yesterday(), true);
        // 끄기 전에 만들어 골라 둔 칸
        letterActionRepository.saveAndFlush(LetterAction.create(letter.getId(), 0, "rest-walk", "산책 10분",
                LetterActionSource.CATALOG, true));

        assertThat(letterService.byDate(user.getId(), yesterday()).actions()).isEmpty();
        assertThatThrownBy(() -> letterService.chooseAction(user.getId(), letter.getId(), 0, null, null, null))
                .isInstanceOf(LetterActionNotFoundException.class);
        assertThat(letterActionRepository.findByLetterIdAndChosenTrue(letter.getId())).isPresent();

        RetrospectResult started = retrospectService.start(user.getId(), StartCommand.today("정민", null));
        assertThat(started.reply().text()).isEqualTo("정민님, 오늘 하루 중에서 가장 기억에 남는 일은 뭐였어요?");
        assertThat(started.reply().options()).isNull();
    }

    @Test
    void nextLetterDoesNotMentionYesterdaysChosenAction() {
        User user = userRepository.saveAndFlush(User.create());
        Letter before = seed(user, yesterday().minusDays(1), true);
        letterActionRepository.saveAndFlush(LetterAction.create(before.getId(), 0, "rest-walk", "산책 10분",
                LetterActionSource.CATALOG, true));

        seed(user, yesterday(), true);

        ArgumentCaptor<LetterMaterials> materials = ArgumentCaptor.forClass(LetterMaterials.class);
        verify(writer, atLeastOnce()).writeBody(materials.capture());
        List<String> previous = materials.getAllValues().stream().map(LetterMaterials::previousAction).toList();
        assertThat(previous).containsOnlyNulls();
    }

    /** 그 날짜의 회고 · 일기 · 바람카드 · 편지. {@code background} 면 회고 뒤처럼 편지를 미리 만들어 둔다. */
    private Letter seed(User user, LocalDate date, boolean background) {
        RetrospectState state = new RetrospectState(UUID.randomUUID().toString());
        state.addUserMessage("발표 전에 손이 떨렸어요");
        Retrospect retrospect = retrospectRepository.save(Retrospect.start(user.getId(),
                RetrospectStatus.COMPLETED, null, codec.serialize(state)));
        Diary diary = diaryRepository.save(Diary.create(user.getId(), retrospect.getId(), "발표가 있었다.", null, null));
        jdbc.update("update diaries set created_at = ?::date + interval '13 hours' where id = ?",
                date.toString(), diary.getId());
        actionCardRepository.save(ActionCard.createWish(user.getId(), retrospect.getId(), "발표", null, date,
                null, "휴식", null, null));
        Letter letter = letterRepository.save(Letter.pending(user.getId(), diary.getId(), date));
        if (background) {
            generator.generateForDiary(diary.getId());
        }
        return letter;
    }

    private static LocalDate yesterday() {
        return DayBoundary.today().minusDays(1);
    }
}
