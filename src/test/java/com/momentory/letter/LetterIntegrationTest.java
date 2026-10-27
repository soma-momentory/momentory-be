package com.momentory.letter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.momentory.actioncard.domain.ActionCard;
import com.momentory.actioncard.infrastructure.persistence.ActionCardRepository;
import com.momentory.auth.token.application.AccessTokenIssuer;
import com.momentory.common.time.DayBoundary;
import com.momentory.diary.application.DiaryService;
import com.momentory.diary.domain.Diary;
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.letter.application.LetterGenerator;
import com.momentory.letter.application.LetterService;
import com.momentory.letter.domain.Letter;
import com.momentory.letter.domain.LetterAction;
import com.momentory.letter.domain.LetterActionSource;
import com.momentory.letter.domain.LetterRules;
import com.momentory.letter.domain.LetterStatus;
import com.momentory.letter.domain.NeedsSource;
import com.momentory.letter.infrastructure.LetterActionRepository;
import com.momentory.letter.infrastructure.LetterRepository;
import com.momentory.letter.infrastructure.ai.GeminiLetterWriter;
import com.momentory.letter.infrastructure.ai.GeminiLetterWriter.Body;
import com.momentory.retrospect.application.RetrospectCompleted;
import com.momentory.retrospect.domain.Need;
import com.momentory.retrospect.domain.NeedActionCatalog;
import com.momentory.retrospect.domain.Needs;
import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.RetrospectStatus;
import com.momentory.retrospect.domain.safety.SafetyLevel;
import com.momentory.retrospect.domain.safety.SafetyPolicy;
import com.momentory.retrospect.infrastructure.persistence.Retrospect;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.retrospect.infrastructure.persistence.RetrospectStateCodec;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;

/**
 * 바바의 편지 — 생성 흐름(회고 커밋 뒤 · 열 때 보충)과 API. Gemini 는 {@link GeminiLetterWriter} 를
 * 바꿔 끼워 흉내 낸다(실패 · 시간 초과 · 안전 차단은 모두 writer 가 empty 를 돌려주는 것으로 모인다 —
 * 그 변환은 {@code GeminiLetterWriterTest} · {@code GeminiApiClientTest} 가 본다).
 */
@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers(disabledWithoutDocker = true)
class LetterIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"));

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String USER_WORD = "발표 전에 손이 계속 떨렸어요";
    private static final String DIARY = "오늘은 팀 발표가 있었다. 시작 전에 손이 떨렸지만 끝까지 말했다.";
    private static final Need REST = Needs.byWord("휴식").orElseThrow();

    @MockitoBean GeminiLetterWriter writer;

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired RetrospectRepository retrospectRepository;
    @Autowired DiaryRepository diaryRepository;
    @Autowired ActionCardRepository actionCardRepository;
    @Autowired LetterRepository letterRepository;
    @Autowired LetterActionRepository letterActionRepository;
    @Autowired RetrospectStateCodec codec;
    @Autowired TransactionTemplate transaction;
    @Autowired ApplicationEventPublisher events;
    @Autowired LetterGenerator generator;
    @Autowired LetterService letterService;
    @Autowired DiaryService diaryService;
    @Autowired NeedActionCatalog catalog;
    @Autowired SafetyPolicy safetyPolicy;
    @Autowired AccessTokenIssuer accessTokenIssuer;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @AfterEach
    void cleanUp() {
        // 커밋 뒤에 띄운 생성이 다음 테스트의 writer 호출 수 · 정리와 겹치지 않게 끝나기를 기다린다.
        await().atMost(Duration.ofSeconds(15)).until(() -> letterRepository.findAll().stream()
                .noneMatch(l -> l.getStatus() == LetterStatus.GENERATING));
        letterRepository.deleteAllInBatch();
        actionCardRepository.deleteAllInBatch();
        diaryRepository.deleteAllInBatch();
        retrospectRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    // ── 생성 흐름 ────────────────────────────────────────────────────────

    @Test
    void completedRetrospectLeavesPendingRowInSameTransactionAndGeneratesAfterCommit() {
        when(writer.writeBody(any())).thenReturn(Optional.of(
                new Body(USER_WORD, "떨렸던 건 그만큼 잘하고 싶었기 때문이었을지도 몰라요.", "오늘 밤엔 발표 뒤 마음부터 물어볼게요.")));
        when(writer.pickNeeds(any())).thenReturn(List.of(REST));
        List<String> restIds = catalog.candidatesFor(List.of(REST)).stream().map(NeedActionCatalog.Action::id).toList();
        when(writer.pickActionIds(any(), anyList())).thenReturn(Optional.of(List.of(restIds.get(1), restIds.get(0))));
        User user = userRepository.saveAndFlush(User.create());

        Long diaryId = transaction.execute(status -> {
            Diary diary = diaryOf(user, false);
            events.publishEvent(completed(diary));
            return diary.getId();
        });

        await().atMost(Duration.ofSeconds(10)).until(() -> letterRepository.findByDiaryId(diaryId)
                .map(l -> l.getStatus() == LetterStatus.READY).orElse(false));
        Letter letter = letterRepository.findByDiaryId(diaryId).orElseThrow();
        assertThat(letter.getLetterDate()).isEqualTo(DayBoundary.today());
        assertThat(letter.getQuote()).isEqualTo(USER_WORD);
        assertThat(letter.getNeedsSource()).isEqualTo(NeedsSource.INFERRED);
        assertThat(letter.getPromptVersion()).isEqualTo("v1");
        assertThat(letterActionRepository.findByLetterIdOrderByPositionAsc(letter.getId()))
                .extracting(LetterAction::getActionId, LetterAction::getSource)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(restIds.get(1), LetterActionSource.CATALOG),
                        org.assertj.core.groups.Tuple.tuple(restIds.get(0), LetterActionSource.CATALOG),
                        org.assertj.core.groups.Tuple.tuple(restIds.get(2), LetterActionSource.FALLBACK));
    }

    @Test
    void failedGenerationKeepsDiaryAndLeavesFailedRowForOpen() {
        User user = userRepository.saveAndFlush(User.create());

        Long diaryId = transaction.execute(status -> {
            Diary diary = diaryOf(user, false);
            events.publishEvent(completed(diary));
            return diary.getId();
        });

        await().atMost(Duration.ofSeconds(10)).until(() -> letterRepository.findByDiaryId(diaryId)
                .map(l -> l.getStatus() == LetterStatus.FAILED).orElse(false));
        assertThat(diaryRepository.findById(diaryId)).isPresent();
        assertThat(letterRepository.findByDiaryId(diaryId).orElseThrow().getAttemptCount()).isEqualTo(1);
    }

    @Test
    void pendingRowIsWrittenInRetrospectTransactionAndRollsBackWithIt() {
        User user = userRepository.saveAndFlush(User.create());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            events.publishEvent(completed(diaryOf(user, false)));
            // 편지 리스너(BEFORE_COMMIT) 다음에 도는 커밋 직전 실패 — 같은 트랜잭션이면 PENDING 행도 함께 사라진다.
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            assertThat(letterRepository.count()).isEqualTo(1);
                            throw new IllegalStateException("커밋 직전 실패");
                        }
                    });
        })).isInstanceOf(IllegalStateException.class);

        assertThat(letterRepository.count()).isZero();
        assertThat(diaryRepository.count()).isZero();
        verifyNoInteractions(writer);
    }

    @Test
    void editDuringFirstGenerationTriggersOneMoreBackgroundRun() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        when(writer.writeBody(any())).thenAnswer(inv -> {
            writing.countDown();
            Thread.sleep(800);
            return Optional.of(new Body("", "첫 관점", "질문"));
        }).thenReturn(Optional.of(new Body("", "고친 뒤 관점", "질문")));
        User user = userRepository.saveAndFlush(User.create());
        Diary diary = diaryOf(user, false);
        Letter letter = letterRepository.save(Letter.pending(user.getId(), diary.getId(), yesterday()));

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> generator.generateForDiary(diary.getId()));
        writing.await();
        diaryService.updateOriginalByRetrospect(user.getId(), diary.getRetrospectId(), "고친 일기");
        first.get();

        await().atMost(Duration.ofSeconds(10)).until(() -> letterRepository.findById(letter.getId())
                .map(l -> "고친 뒤 관점".equals(l.getInsight()) && !l.isStale()).orElse(false));
    }

    @Test
    void openWaitingOnFailedBackgroundTakesOverInsteadOfWaitingOut() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        when(writer.writeBody(any())).thenAnswer(inv -> {
            writing.countDown();
            Thread.sleep(500);
            return Optional.empty(); // 안전 차단 등으로 뒤에서 만들기가 실패한다.
        });
        when(writer.writeBodyOnOpen(any())).thenReturn(Optional.of(new Body("", "열 때 관점", "질문")));
        User user = userRepository.saveAndFlush(User.create());
        Diary diary = diaryOf(user, false);
        letterRepository.save(Letter.pending(user.getId(), diary.getId(), yesterday()));

        CompletableFuture<Void> background = CompletableFuture.runAsync(() -> generator.generateForDiary(diary.getId()));
        writing.await();
        long startedAt = System.nanoTime();
        var view = letterService.byDate(user.getId(), yesterday());
        background.get();

        assertThat(view.status()).isEqualTo(LetterStatus.READY);
        assertThat(view.insight()).isEqualTo("열 때 관점");
        assertThat(view.actions()).hasSize(3);
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void choosingActionWithoutOpenStillProtectsChoiceFromRegeneration() throws Exception {
        when(writer.writeBody(any())).thenReturn(Optional.of(new Body("", "관점", "질문")));
        User user = userRepository.saveAndFlush(User.create());
        Diary diary = diaryOf(user, false);
        Letter letter = letterRepository.save(Letter.pending(user.getId(), diary.getId(), yesterday()));
        generator.generateForDiary(diary.getId());

        // 앱이 열람 기록(POST open)을 빠뜨리고 바로 작은 일을 골랐다.
        mockMvc.perform(authed(put("/api/v1/letters/" + letter.getId() + "/action"), user)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"position\":1,\"whenLabel\":\"점심 뒤\"}"))
                .andExpect(status().isOk());
        diaryService.updateOriginalByRetrospect(user.getId(), diary.getRetrospectId(), "고친 일기");

        Letter saved = letterRepository.findById(letter.getId()).orElseThrow();
        assertThat(saved.getOpenedAt()).isNotNull();
        assertThat(saved.isStale()).isFalse();
        assertThat(letterActionRepository.findByLetterIdAndChosenTrue(letter.getId()).orElseThrow().getWhenLabel())
                .isEqualTo("점심 뒤");
    }

    @Test
    void letterDateFollowsFourAmBoundaryOfDiaryCreation() {
        User user = userRepository.saveAndFlush(User.create());
        Diary lateNight = diaryOf(user, false);
        Diary morning = diaryOf(user, false);
        // 10/27 03:59:59 KST 에 남긴 일기는 10/26 의 것, 04:00 에 남긴 일기는 10/27 의 것이다.
        jdbc.update("update diaries set created_at = '2026-10-26T18:59:59Z' where id = ?", lateNight.getId());
        jdbc.update("update diaries set created_at = '2026-10-26T19:00:00Z' where id = ?", morning.getId());

        transaction.executeWithoutResult(status -> {
            events.publishEvent(completed(lateNight));
            events.publishEvent(completed(morning));
        });

        assertThat(letterRepository.findByDiaryId(lateNight.getId()).orElseThrow().getLetterDate())
                .isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(letterRepository.findByDiaryId(morning.getId()).orElseThrow().getLetterDate())
                .isEqualTo(LocalDate.of(2026, 10, 27));
        await().atMost(Duration.ofSeconds(10)).until(() -> letterRepository.findAll().stream()
                .allMatch(l -> l.getStatus() == LetterStatus.FAILED));
    }

    @Test
    void lateFinisherDoesNotOverwriteAfterItsClaimWasTakenOver() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        when(writer.writeBody(any())).thenAnswer(inv -> {
            writing.countDown();
            Thread.sleep(1000);
            return Optional.of(new Body("", "늦게 끝난 편지", "질문"));
        });
        User user = userRepository.saveAndFlush(User.create());
        Diary diary = diaryOf(user, false);
        Letter letter = letterRepository.save(Letter.pending(user.getId(), diary.getId(), yesterday()));

        CompletableFuture<Void> slow = CompletableFuture.runAsync(() -> generator.generateForDiary(diary.getId()));
        writing.await();
        // 60초가 지나 다른 쪽이 가로챈 것과 같다 — 들어간 시각이 바뀌었다.
        jdbc.update("update letters set generation_started_at = now() + interval '1 second' where id = ?", letter.getId());
        slow.get();

        Letter saved = letterRepository.findById(letter.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(LetterStatus.GENERATING);
        assertThat(saved.getInsight()).isNull();
        assertThat(letterActionRepository.findByLetterIdOrderByPositionAsc(letter.getId())).isEmpty();
        // 가로챈 쪽은 흉내만 냈으므로 정리 단계가 기다리지 않게 끝난 상태로 돌려 둔다.
        jdbc.update("update letters set status = 'FAILED' where id = ?", letter.getId());
    }

    @Test
    void withdrawalDeletesLetters() {
        User user = userRepository.saveAndFlush(User.create());
        letterRepository.save(Letter.pending(user.getId(), diaryOf(user, false).getId(), yesterday()));

        userRepository.deleteById(user.getId());
        userRepository.flush();

        assertThat(letterRepository.count()).isZero();
    }

    @Test
    void crisisLetterOpenedBeforeBackgroundIsQuietToo() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        letterRepository.save(Letter.pending(user.getId(), diaryOf(user, true).getId(), yesterday()));

        mockMvc.perform(authed(get("/api/v1/letters/current"), user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUIET"))
                .andExpect(jsonPath("$.actions.length()").value(0));
        verifyNoInteractions(writer);
    }

    @Test
    void backgroundAndOpenAtTheSameTimeMakeOneLetterWithOneBodyCall() throws Exception {
        Body body = new Body(USER_WORD, "관점", "오늘 밤엔 발표부터 물어볼게요.");
        when(writer.writeBody(any())).thenAnswer(inv -> {
            Thread.sleep(1500);
            return Optional.of(body);
        });
        when(writer.writeBodyOnOpen(any())).thenAnswer(inv -> {
            Thread.sleep(1500);
            return Optional.of(body);
        });
        User user = userRepository.saveAndFlush(User.create());
        Diary diary = diaryOf(user, false);
        Letter letter = letterRepository.save(Letter.pending(user.getId(), diary.getId(), yesterday()));

        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Void> background = CompletableFuture.runAsync(() -> {
            awaitLatch(start);
            generator.generateForDiary(diary.getId());
        });
        CompletableFuture<LetterStatus> open = CompletableFuture.supplyAsync(() -> {
            awaitLatch(start);
            return letterService.byDate(user.getId(), yesterday()).status();
        });
        start.countDown();
        background.get();

        assertThat(open.get()).isEqualTo(LetterStatus.READY);
        assertThat(letterRepository.count()).isEqualTo(1);
        assertThat(letterRepository.findById(letter.getId()).orElseThrow().getAttemptCount()).isEqualTo(1);
        assertThat(mockingDetails(writer).getInvocations().stream()
                .filter(i -> i.getMethod().getName().startsWith("writeBody")).count()).isEqualTo(1);
        assertThat(letterActionRepository.findByLetterIdOrderByPositionAsc(letter.getId()))
                .extracting(LetterAction::getPosition).containsExactly(0, 1, 2);
    }

    @Test
    void geminiFailureOnOpenShowsFallbackLetter() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        Diary diary = diaryOf(user, false);
        Letter letter = letterRepository.save(Letter.pending(user.getId(), diary.getId(), yesterday()));
        List<String> restTitles = catalog.candidatesFor(List.of(REST)).stream()
                .map(NeedActionCatalog.Action::title).limit(3).toList();

        mockMvc.perform(authed(get("/api/v1/letters/" + yesterday()), user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FALLBACK"))
                .andExpect(jsonPath("$.quote").value("오늘은 팀 발표가 있었다."))
                .andExpect(jsonPath("$.insight").value(LetterRules.defaultBody(yesterday())))
                .andExpect(jsonPath("$.tonightQuestion").value(LetterRules.DEFAULT_TONIGHT_QUESTION))
                .andExpect(jsonPath("$.actions.length()").value(3))
                .andExpect(jsonPath("$.actions[0].title").value(restTitles.get(0)))
                .andExpect(jsonPath("$.actions[2].title").value(restTitles.get(2)))
                .andExpect(jsonPath("$.actions[0].source").value("FALLBACK"))
                .andExpect(jsonPath("$.helplines.length()").value(0));

        assertThat(letterRepository.findById(letter.getId()).orElseThrow().getStatus())
                .isEqualTo(LetterStatus.FALLBACK);
        assertThat(meters.get("letter.generation").tags("path", "open", "status", "FALLBACK").timer().count())
                .isGreaterThanOrEqualTo(1);
        verify(writer).writeBodyOnOpen(any());
        verify(writer, never()).pickActionIds(any(), anyList());
    }

    @Test
    void openPathAsksNeedsThenActionIdsAlongsideBody() throws Exception {
        Need relation = Needs.byWord("관계").orElseThrow();
        List<String> relationIds = catalog.candidatesFor(List.of(relation)).stream()
                .map(NeedActionCatalog.Action::id).toList();
        when(writer.writeBodyOnOpen(any())).thenAnswer(inv -> {
            Thread.sleep(700);
            return Optional.of(new Body("", "관점", "오늘 밤엔 결혼식 얘기부터 물어볼게요."));
        });
        when(writer.pickNeedsOnOpen(any())).thenAnswer(inv -> {
            Thread.sleep(350);
            return List.of(relation);
        });
        when(writer.pickActionIdsOnOpen(any(), anyList())).thenAnswer(inv -> {
            Thread.sleep(350);
            return Optional.of(List.of(relationIds.get(3), relationIds.get(1)));
        });
        User user = userRepository.saveAndFlush(User.create());
        Letter letter = letterRepository.save(Letter.pending(user.getId(), diaryOf(user, false).getId(), yesterday()));

        long startedAt = System.nanoTime();
        letterService.byDate(user.getId(), yesterday());

        // 본문과 「욕구 → 번호」가 동시에 돌아 둘 중 긴 쪽만큼만 걸린다.
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofMillis(1300));
        Letter saved = letterRepository.findById(letter.getId()).orElseThrow();
        assertThat(saved.getNeedsSource()).isEqualTo(NeedsSource.INFERRED);
        assertThat(saved.getNeeds()).isEqualTo("관계");
        assertThat(letterActionRepository.findByLetterIdOrderByPositionAsc(letter.getId()))
                .extracting(LetterAction::getActionId, LetterAction::getSource)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(relationIds.get(3), LetterActionSource.CATALOG),
                        org.assertj.core.groups.Tuple.tuple(relationIds.get(1), LetterActionSource.CATALOG),
                        org.assertj.core.groups.Tuple.tuple(relationIds.get(0), LetterActionSource.FALLBACK));
        verify(writer, never()).pickActionIds(any(), anyList());
    }

    @Test
    void openPathFallbackLetterAlsoUsesInferredNeeds() throws Exception {
        Need relation = Needs.byWord("관계").orElseThrow();
        when(writer.pickNeedsOnOpen(any())).thenReturn(List.of(relation));
        User user = userRepository.saveAndFlush(User.create());
        Letter letter = letterRepository.save(Letter.pending(user.getId(), diaryOf(user, false).getId(), yesterday()));

        letterService.byDate(user.getId(), yesterday());

        Letter saved = letterRepository.findById(letter.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(LetterStatus.FALLBACK);
        assertThat(saved.getNeedsSource()).isEqualTo(NeedsSource.INFERRED);
        assertThat(letterActionRepository.findByLetterIdOrderByPositionAsc(letter.getId()))
                .extracting(LetterAction::getActionId)
                .containsExactlyElementsOf(catalog.candidatesFor(List.of(relation)).stream()
                        .map(NeedActionCatalog.Action::id).limit(3).toList());
    }

    @Test
    void unexpectedErrorAfterClaimLeavesFailedNotStuckGenerating() {
        User user = userRepository.saveAndFlush(User.create());
        Diary diary = diaryOf(user, false);
        Letter letter = letterRepository.save(Letter.pending(user.getId(), diary.getId(), yesterday()));
        // 회고 상태가 깨져 재료를 읽다 예외가 난다.
        jdbc.update("update retrospects set state_json = 'not json' where id = ?", diary.getRetrospectId());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> letterService.byDate(user.getId(), yesterday()))
                .isInstanceOf(RuntimeException.class);

        // 60초 동안 GENERATING 에 묶이지 않고, 다음 요청이 바로 다시 들어갈 수 있다.
        assertThat(letterRepository.findById(letter.getId()).orElseThrow().getStatus()).isEqualTo(LetterStatus.FAILED);
    }

    @Test
    void fabricatedQuoteIsDroppedButLetterStaysReady() {
        when(writer.writeBody(any())).thenReturn(Optional.of(new Body("사실 아무 일도 없었어요", "관점",
                "오늘 밤엔 무슨 이야기부터 물어볼게요.")));
        User user = userRepository.saveAndFlush(User.create());
        Diary diary = diaryOf(user, false);
        Letter letter = letterRepository.save(Letter.pending(user.getId(), diary.getId(), yesterday()));

        double droppedBefore = meters.counter("letter.quote.dropped").count();

        generator.generateForDiary(diary.getId());

        Letter saved = letterRepository.findById(letter.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(LetterStatus.READY);
        assertThat(saved.getQuote()).isNull();
        assertThat(meters.counter("letter.quote.dropped").count()).isEqualTo(droppedBefore + 1);
        assertThat(saved.getInsight()).isEqualTo("관점");
        // 틀은 맞아도 대상이 비어 있는 예고는 기본 문장으로 바뀐다.
        assertThat(saved.getTonightQuestion()).isEqualTo(LetterRules.DEFAULT_TONIGHT_QUESTION);
    }

    @Test
    void actionIdsOutsideCatalogOtherNeedOrDuplicatedAreDroppedAndFilledByFallback() {
        when(writer.writeBody(any())).thenReturn(Optional.of(new Body("", "관점", "질문")));
        when(writer.pickNeeds(any())).thenReturn(List.of(REST));
        List<NeedActionCatalog.Action> rest = catalog.candidatesFor(List.of(REST));
        String otherNeedId = catalog.snapshot().actions().stream().map(NeedActionCatalog.Action::id)
                .filter(id -> rest.stream().noneMatch(a -> a.id().equals(id))).findFirst().orElseThrow();
        String valid = rest.get(2).id();
        when(writer.pickActionIds(any(), anyList()))
                .thenReturn(Optional.of(List.of("Z99", otherNeedId, valid, valid)));
        User user = userRepository.saveAndFlush(User.create());
        Diary diary = diaryOf(user, false);
        Letter letter = letterRepository.save(Letter.pending(user.getId(), diary.getId(), yesterday()));

        generator.generateForDiary(diary.getId());

        assertThat(letterActionRepository.findByLetterIdOrderByPositionAsc(letter.getId()))
                .extracting(LetterAction::getActionId, LetterAction::getSource)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(valid, LetterActionSource.CATALOG),
                        org.assertj.core.groups.Tuple.tuple(rest.get(0).id(), LetterActionSource.FALLBACK),
                        org.assertj.core.groups.Tuple.tuple(rest.get(1).id(), LetterActionSource.FALLBACK));
    }

    @Test
    void pendingRowLeftByRestartIsGeneratedWhenOpened() throws Exception {
        when(writer.writeBodyOnOpen(any())).thenReturn(Optional.of(new Body(USER_WORD, "관점", "질문")));
        User user = userRepository.saveAndFlush(User.create());
        Diary diary = diaryOf(user, false);
        // 회고는 커밋됐지만 뒤에서 만들기 전에 서버가 내려간 상태 — 행만 PENDING 으로 남았다.
        letterRepository.save(Letter.pending(user.getId(), diary.getId(), yesterday()));

        mockMvc.perform(authed(get("/api/v1/letters/current"), user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.quote").value(USER_WORD))
                .andExpect(jsonPath("$.isNew").value(true))
                .andExpect(jsonPath("$.actions.length()").value(3));

        verify(writer, never()).writeBody(any());
        verify(writer, never()).pickActionIds(any(), anyList());
    }

    @Test
    void crisisRetrospectGetsQuietLetterWithoutCallingModel() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        Diary diary = diaryOf(user, true);
        letterRepository.save(Letter.pending(user.getId(), diary.getId(), yesterday()));

        generator.generateForDiary(diary.getId());

        verifyNoInteractions(writer);
        mockMvc.perform(authed(get("/api/v1/letters/" + yesterday()), user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUIET"))
                .andExpect(jsonPath("$.quote").doesNotExist())
                .andExpect(jsonPath("$.tonightQuestion").doesNotExist())
                .andExpect(jsonPath("$.actions.length()").value(0))
                .andExpect(jsonPath("$.helplines[0]").value(safetyPolicy.resources().get(0)));
    }

    @Test
    void wishCardActionTakesFirstSlotAsChosenAndItsNeedsAreUsed() {
        when(writer.writeBody(any())).thenReturn(Optional.of(new Body("", "관점", "질문")));
        when(writer.pickActionIds(any(), anyList())).thenReturn(Optional.of(List.of()));
        NeedActionCatalog.Action wishAction = catalog.candidatesFor(List.of(REST)).get(0);
        User user = userRepository.saveAndFlush(User.create());
        Diary diary = diaryOf(user, false);
        actionCardRepository.save(ActionCard.createWish(user.getId(), diary.getRetrospectId(), "발표",
                wishAction.title(), yesterday(), null, "휴식", null, null));
        Letter letter = letterRepository.save(Letter.pending(user.getId(), diary.getId(), yesterday()));

        generator.generateForDiary(diary.getId());

        verify(writer, never()).pickNeeds(any());
        Letter saved = letterRepository.findById(letter.getId()).orElseThrow();
        assertThat(saved.getNeedsSource()).isEqualTo(NeedsSource.WISH_CARD);
        assertThat(saved.getNeeds()).isEqualTo("휴식");
        List<LetterAction> actions = letterActionRepository.findByLetterIdOrderByPositionAsc(letter.getId());
        assertThat(actions).hasSize(3);
        assertThat(actions.get(0).getSource()).isEqualTo(LetterActionSource.WISH_CARD);
        assertThat(actions.get(0).getActionId()).isEqualTo(wishAction.id());
        assertThat(actions.get(0).isChosen()).isTrue();
        assertThat(actions.subList(1, 3)).extracting(LetterAction::getActionId).doesNotContain(wishAction.id());
    }

    @Test
    void editingDiaryRegeneratesUnopenedLetterButNotOpenedOne() {
        when(writer.writeBody(any())).thenReturn(Optional.of(new Body("", "고친 뒤 관점", "질문")));
        User user = userRepository.saveAndFlush(User.create());
        Diary unopened = diaryOf(user, false);
        Letter first = letterRepository.save(Letter.pending(user.getId(), unopened.getId(), yesterday()));
        generator.generateForDiary(unopened.getId());
        when(writer.writeBody(any())).thenReturn(Optional.of(new Body("", "두 번째 관점", "질문")));

        diaryService.updateOriginalByRetrospect(user.getId(), unopened.getRetrospectId(), "고친 일기");

        await().atMost(Duration.ofSeconds(10)).until(() -> letterRepository.findById(first.getId())
                .map(l -> "두 번째 관점".equals(l.getInsight()) && !l.isStale()).orElse(false));

        Diary opened = diaryOf(user, false);
        Letter second = letterRepository.save(Letter.pending(user.getId(), opened.getId(), yesterday().minusDays(1)));
        generator.generateForDiary(opened.getId());
        letterService.open(user.getId(), second.getId());

        diaryService.updateOriginalByRetrospect(user.getId(), opened.getRetrospectId(), "고친 일기");

        assertThat(letterRepository.findById(second.getId()).orElseThrow().isStale()).isFalse();
    }

    @Test
    void deletingDiaryDeletesLetterAndActions() {
        when(writer.writeBody(any())).thenReturn(Optional.of(new Body("", "관점", "질문")));
        User user = userRepository.saveAndFlush(User.create());
        Diary diary = diaryOf(user, false);
        Letter letter = letterRepository.save(Letter.pending(user.getId(), diary.getId(), yesterday()));
        generator.generateForDiary(diary.getId());
        assertThat(letterActionRepository.findByLetterIdOrderByPositionAsc(letter.getId())).hasSize(3);

        diaryService.delete(user.getId(), diary.getId());

        assertThat(letterRepository.findById(letter.getId())).isEmpty();
        assertThat(letterActionRepository.count()).isZero();
    }

    // ── API ──────────────────────────────────────────────────────────────

    @Test
    void letterOpensFromNextDayAndListsNewestFirst() throws Exception {
        when(writer.writeBodyOnOpen(any())).thenReturn(Optional.of(new Body("", "관점", "질문")));
        User user = userRepository.saveAndFlush(User.create());
        letterRepository.save(Letter.pending(user.getId(), diaryOf(user, false).getId(), DayBoundary.today()));

        // 오늘 일기의 편지는 내일 04:00 부터 열린다.
        mockMvc.perform(authed(get("/api/v1/letters/current"), user)).andExpect(status().isNoContent());
        mockMvc.perform(authed(get("/api/v1/letters/" + DayBoundary.today()), user))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LETTER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("편지를 찾을 수 없습니다."));

        Letter yesterday = letterRepository.save(Letter.pending(user.getId(), diaryOf(user, false).getId(), yesterday()));
        Letter old = letterRepository.save(Letter.pending(user.getId(), diaryOf(user, false).getId(),
                DayBoundary.today().minusDays(3)));

        mockMvc.perform(authed(get("/api/v1/letters"), user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.letters.length()").value(2))
                .andExpect(jsonPath("$.letters[0].id").value(yesterday.getId()))
                .andExpect(jsonPath("$.letters[0].isNew").value(true))
                .andExpect(jsonPath("$.letters[1].id").value(old.getId()))
                .andExpect(jsonPath("$.letters[1].isNew").value(false));
        // 목록은 편지를 만들지 않는다.
        verifyNoInteractions(writer);

        mockMvc.perform(authed(get("/api/v1/letters/current"), user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(yesterday.getId()))
                .andExpect(jsonPath("$.date").value(yesterday().toString()));
    }

    @Test
    void openRecordsFirstTimeOnlyAndHidesOtherUsersLetters() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        User other = userRepository.saveAndFlush(User.create());
        Letter letter = letterRepository.save(Letter.pending(user.getId(), diaryOf(user, false).getId(), yesterday()));

        mockMvc.perform(authed(post("/api/v1/letters/" + letter.getId() + "/open"), user))
                .andExpect(status().isNoContent());
        var firstOpened = letterRepository.findById(letter.getId()).orElseThrow().getOpenedAt();
        assertThat(firstOpened).isNotNull();
        mockMvc.perform(authed(post("/api/v1/letters/" + letter.getId() + "/open"), user))
                .andExpect(status().isNoContent());
        assertThat(letterRepository.findById(letter.getId()).orElseThrow().getOpenedAt()).isEqualTo(firstOpened);

        mockMvc.perform(authed(post("/api/v1/letters/" + letter.getId() + "/open"), other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LETTER_NOT_FOUND"));
        mockMvc.perform(authed(get("/api/v1/letters/" + yesterday()), other))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/letters/current")).andExpect(status().isUnauthorized());
    }

    @Test
    void choosingActionKeepsOnlyOneChosenAndCanBeCleared() throws Exception {
        when(writer.writeBody(any())).thenReturn(Optional.of(new Body("", "관점", "질문")));
        User user = userRepository.saveAndFlush(User.create());
        Diary diary = diaryOf(user, false);
        Letter letter = letterRepository.save(Letter.pending(user.getId(), diary.getId(), yesterday()));
        generator.generateForDiary(diary.getId());
        String path = "/api/v1/letters/" + letter.getId() + "/action";

        mockMvc.perform(authed(put(path), user).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":0,\"whenLabel\":\"점심 뒤\",\"remindAt\":\"2026-10-27T03:30:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[0].chosen").value(true))
                .andExpect(jsonPath("$.actions[0].whenLabel").value("점심 뒤"))
                .andExpect(jsonPath("$.actions[0].remindAt").value("2026-10-27T03:30:00Z"));
        mockMvc.perform(authed(put(path), user).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":2,\"whenLabel\":\"자기 전\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[0].chosen").value(false))
                .andExpect(jsonPath("$.actions[0].whenLabel").doesNotExist())
                .andExpect(jsonPath("$.actions[2].chosen").value(true));
        assertThat(letterActionRepository.findByLetterIdAndChosenTrue(letter.getId()).orElseThrow().getPosition())
                .isEqualTo(2);

        mockMvc.perform(authed(put(path), user).contentType(MediaType.APPLICATION_JSON).content("{\"position\":null}"))
                .andExpect(status().isOk());
        assertThat(letterActionRepository.findByLetterIdAndChosenTrue(letter.getId())).isEmpty();

        mockMvc.perform(authed(put(path), user).contentType(MediaType.APPLICATION_JSON).content("{\"position\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("작은 일 칸은 0부터 2까지입니다."));
        mockMvc.perform(authed(put(path), user).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":1,\"whenLabel\":\"" + "가".repeat(31) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("「언제」는 최대 30자입니다."));
        mockMvc.perform(authed(put(path), user).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":1,\"scheduleId\":999999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("일정을 찾을 수 없습니다."));
        assertThat(letterActionRepository.findByLetterIdAndChosenTrue(letter.getId())).isEmpty();
    }

    @Test
    void quietLetterHasNoActionToChoose() throws Exception {
        User user = userRepository.saveAndFlush(User.create());
        Diary diary = diaryOf(user, true);
        Letter letter = letterRepository.save(Letter.pending(user.getId(), diary.getId(), yesterday()));
        generator.generateForDiary(diary.getId());

        mockMvc.perform(authed(put("/api/v1/letters/" + letter.getId() + "/action"), user)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"position\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LETTER_ACTION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("편지에 그 작은 일이 없습니다."));
    }

    // ── 도우미 ───────────────────────────────────────────────────────────

    /** 회고 한 벌 + 일기. {@code crisis} 면 대화에 위기 안내가 남아 있다(「이어서 얘기하기」로 상태는 지워짐). */
    private Diary diaryOf(User user, boolean crisis) {
        RetrospectState state = new RetrospectState(UUID.randomUUID().toString());
        state.addAssistantMessage("오늘 어떤 일이 있었어요?");
        state.addUserMessage(USER_WORD);
        if (crisis) {
            state.addUserMessage("너무 힘들어서 사라지고 싶어요");
            state.addAssistantMessage(safetyPolicy.guidanceFor(SafetyLevel.IMMINENT).render());
            state.addUserMessage("이어서 얘기할게요");
        }
        Retrospect retrospect = retrospectRepository.save(Retrospect.start(user.getId(),
                RetrospectStatus.COMPLETED, null, codec.serialize(state)));
        return diaryRepository.save(Diary.create(user.getId(), retrospect.getId(), DIARY, null, null));
    }

    private static RetrospectCompleted completed(Diary diary) {
        return new RetrospectCompleted(diary.getRetrospectId(), diary.getUserId(),
                new RetrospectCompleted.DiaryData(null, DIARY, List.of()), null, List.of());
    }

    private static LocalDate yesterday() {
        return DayBoundary.today().minusDays(1);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request, User user) {
        return request.header(HttpHeaders.AUTHORIZATION,
                "Bearer " + accessTokenIssuer.issueAccessToken(user.getId(), user.getRole()));
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
