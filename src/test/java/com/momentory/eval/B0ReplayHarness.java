package com.momentory.eval;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.momentory.diary.application.DiaryService;
import com.momentory.diary.domain.Diary;
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.letter.application.LetterService;
import com.momentory.letter.application.LetterView;
import com.momentory.letter.domain.Letter;
import com.momentory.letter.infrastructure.LetterRepository;
import com.momentory.letter.application.LetterVerification;
import com.momentory.letter.application.RiskJudgment;
import com.momentory.letter.domain.LetterPolicy;
import com.momentory.letter.infrastructure.ai.GeminiLetterVerifier;
import com.momentory.letter.infrastructure.ai.GeminiLetterWriter;
import com.momentory.letter.infrastructure.ai.GeminiLetterWriter.Body;
import com.momentory.letter.infrastructure.ai.GeminiRiskJudge;
import com.momentory.retrospect.application.ReplyDto;
import com.momentory.retrospect.application.RetrospectService;
import com.momentory.retrospect.application.StartCommand;
import com.momentory.retrospect.application.TurnCommand;
import com.momentory.retrospect.application.event.CrisisDetected;
import com.momentory.retrospect.domain.Message;
import com.momentory.retrospect.domain.assistant.DiaryChatAssistant;
import com.momentory.retrospect.domain.assistant.DiaryOutput;
import com.momentory.retrospect.domain.assistant.DiaryTurn;
import com.momentory.retrospect.domain.assistant.DiaryWriter;
import com.momentory.retrospect.domain.assistant.EmotionExtraction;
import com.momentory.retrospect.domain.assistant.EmotionExtractor;
import com.momentory.retrospect.domain.safety.SafetyLevel;
import com.momentory.retrospect.domain.safety.SafetyPolicy;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.retrospect.infrastructure.persistence.RetrospectStateCodec;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B0 재생 하네스(AI 안전성 연구 설계 8.2 · 8.4절). 합성 사례마다 지금 운영 경로에 실제 빈을 그대로 흘려 넣는다:
 * 회고 대화(사용자 말마다 {@code SafetyPolicy.scan} + 대화 LLM 의 safetyLevel 누적 → 위기 안내) → 회고 저장
 * → 편지 행 · 생성(위기 안내가 남았으면 QUIET) → 다음 날 열기. 사례 하나에 JSONL 한 줄을 남긴다.
 *
 * <p>여러 날 사례는 마지막 날만 재생한다. 지금 편지 재료가 지난 일기를 쓰지 않기 때문이다. 편지 항목(L)은
 * {@code baseItemId} 사례를 재생하되 편지 본문 생성 결과를 데이터셋 편지로 바꿔, B0 가 그 편지를 막는지
 * 그대로 전달하는지 본다(B0 에는 내용 검증기가 없다).
 *
 * <p>흉내 내는 지점은 결과 줄의 {@code mocked} 에 남긴다. 기본(eval.mode=mock)은 Gemini 를 부르지 않는다.
 * eval.mode=real 이면 회고 대화 턴과 편지 본문 · 욕구 · 작은 일 고르기만 실제 Gemini 를 부른다(합성 데이터만).
 * 감정 추출과 일기 쓰기는 두 모드 모두 흉내 낸다: 일기는 데이터셋 원문을 그대로 쓰고, 감정 탐색은 건너뛴다.
 *
 * <p>기본 {@code ./gradlew test} 에서는 eval 태그로 빠지고, {@code ./gradlew evalB0} 로만 돈다.
 */
@Tag("eval")
@SpringBootTest(properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d",
        "KAKAO_APP_ID=123456789"
})
@Testcontainers
@RecordApplicationEvents
class B0ReplayHarness {

    private static final boolean REAL = "real".equals(System.getProperty("eval.mode", "mock"));
    /** 편지 안전 게이트(P1) — OFF 면 B0 · P0 재생과 같다. */
    private static final String GATE = System.getProperty("LETTER_GATE_MODE", "OFF");
    /** 판정자 흉내: oracle(데이터셋 제안 라벨을 판정으로 · 판정자의 상한) · none(늘 실패 · 하한) · real */
    private static final String JUDGE = System.getProperty("eval.judge", "oracle");
    /** 검증기 흉내: oracle(데이터셋 편지의 위반 라벨) · pass(늘 통과) · real */
    private static final String VERIFIER = System.getProperty("eval.verifier", "oracle");
    private static final Body NEUTRAL_BODY =
            new Body("", "오늘 하루를 차분히 돌아본 마음이 보여요.", "오늘 밤엔 하루 이야기부터 물어볼게요.");
    private static final String STOP = "그만할래요";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"));

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        if (REAL) {
            String key = System.getenv("GEMINI_API_KEY");
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("eval.mode=real 에는 GEMINI_API_KEY 가 필요하다");
            }
            registry.add("gemini.api.api-key", () -> key);
        }
    }

    @MockitoSpyBean DiaryChatAssistant chat;
    @MockitoSpyBean EmotionExtractor extractor;
    @MockitoSpyBean DiaryWriter diaryWriter;
    @MockitoSpyBean GeminiLetterWriter writer;
    @MockitoSpyBean GeminiRiskJudge judge;
    @MockitoSpyBean GeminiLetterVerifier verifier;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;

    @Autowired RetrospectService retrospectService;
    @Autowired RetrospectRepository retrospectRepository;
    @Autowired RetrospectStateCodec codec;
    @Autowired DiaryRepository diaryRepository;
    @Autowired DiaryService diaryService;
    @Autowired LetterRepository letterRepository;
    @Autowired LetterService letterService;
    @Autowired UserRepository userRepository;
    @Autowired SafetyPolicy safetyPolicy;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEvents events;

    private final JsonMapper json = JsonMapper.builder().build();

    // 지금 재생하는 사례의 재료 — 흉내 낸 빈이 읽는다. 편지 생성은 다른 스레드에서 돌지만, 끝날 때까지 기다린 뒤
    // 다음 사례로 넘어가므로 사례끼리 섞이지 않는다.
    private volatile String diaryText;
    private volatile JsonNode datasetLetter;
    private volatile boolean failLetter;
    private volatile String editedDiary;
    private final List<String> llmLevels = new CopyOnWriteArrayList<>();
    private final List<String> judgeOut = new CopyOnWriteArrayList<>();
    private final List<String> verdicts = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger guardedBodies = new java.util.concurrent.atomic.AtomicInteger();
    private List<JsonNode> pastDays = List.of();
    private JsonNode baseProposed;
    private JsonNode letterProposed;

    @Test
    void replay() throws IOException {
        Path data = Path.of(required("eval.data"));
        Path out = Path.of(required("eval.out"));
        String system = System.getProperty("eval.system", "B0-pre");
        // codeCommit 은 운영 코드(src/main)를 마지막으로 바꾼 커밋이다. 하네스 커밋이 그 위에 얹혀도 B0 버전은 그대로다.
        String commit = git("log", "-1", "--format=%H", "--", "src/main");
        String harnessCommit = git("rev-parse", "HEAD");
        boolean dirty = !git("status", "--porcelain", "--", "src/main").isEmpty();
        stub();

        Map<String, JsonNode> items = new LinkedHashMap<>();
        for (String line : Files.readAllLines(data, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                JsonNode item = json.readTree(line);
                items.put(item.get("itemId").asString(), item);
            }
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        List<String> lines = new ArrayList<>();
        for (JsonNode item : items.values()) {
            Map<String, Object> row = replayOne(item, items);
            row.put("system", system);
            row.put("codeCommit", commit);
            row.put("harnessCommit", harnessCommit);
            row.put("codeDirty", dirty);
            lines.add(json.writeValueAsString(row));
        }
        Files.write(out, lines, StandardCharsets.UTF_8);
        System.out.println("[eval] " + lines.size() + " rows -> " + out);
    }

    private Map<String, Object> replayOne(JsonNode item, Map<String, JsonNode> items) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("itemId", item.get("itemId").asString());
        row.put("family", text(item, "family"));
        row.put("bundle", text(item, "bundle"));
        row.put("kind", text(item, "kind"));
        row.put("split", text(item, "split"));
        row.put("run", 1);
        JsonNode label = item.path("label");
        boolean reviewed = label.isObject() && !"unreviewed".equals(label.path("status").asString(""));
        row.put("gold", reviewed ? label : item.path("proposed"));
        row.put("goldSource", reviewed ? "label" : "proposed");
        List<String> errors = new ArrayList<>();
        row.put("errors", errors);

        JsonNode source = item;
        datasetLetter = null;
        if (item.path("letter").isObject()) {
            datasetLetter = item.get("letter");
            source = items.get(datasetLetter.path("baseItemId").asString(""));
            if (source == null) {
                errors.add("baseItemId_not_found");
                row.put("finalStatus", "ERROR");
                return row;
            }
            row.put("baseItemId", source.get("itemId").asString());
        }
        JsonNode days = source.path("days");
        if (days.isEmpty()) {
            errors.add("no_days");
            row.put("finalStatus", "ERROR");
            return row;
        }
        JsonNode today = days.get(days.size() - 1);
        pastDays = new ArrayList<>();
        for (int i = 0; i < days.size() - 1; i++) {
            pastDays.add(days.get(i));
        }
        baseProposed = source.path("proposed");
        letterProposed = datasetLetter != null ? item.path("proposed") : null;
        judgeOut.clear();
        verdicts.clear();
        guardedBodies.set(0);
        List<String> turns = userTurns(today.path("chatTurns"));
        diaryText = today.path("diary").asString("");
        // 데이터셋 칸 diaryEditedAfterRetro: 회고는 diaryBeforeEdit 를 일기로 만들고, 사용자가 그 뒤 diary 로 고쳤다.
        editedDiary = null;
        if (today.path("diaryEditedAfterRetro").asBoolean(false)) {
            editedDiary = diaryText;
            diaryText = today.path("diaryBeforeEdit").asString("");
        }
        row.put("explorationTurnsSkipped", today.path("explorationTurns").size());
        // 선택 칸 simulateLetterFailure: 편지 본문 생성이 실패한 경우(기본 편지 · 인용 조건, P0-4 사례).
        failLetter = item.path("simulateLetterFailure").asBoolean(false);
        llmLevels.clear();
        events.clear();

        // 선택 칸 earlierSessions: 같은 날 먼저 했다가 위기 안내에서 멈춘 채 나간 회고들의 사용자 말(P0-2 사례).
        List<List<String>> earlier = new ArrayList<>();
        for (JsonNode session : today.path("earlierSessions")) {
            earlier.add(userTurns(session));
        }
        List<String> allTurns = new ArrayList<>(turns);
        earlier.forEach(allTurns::addAll);

        try {
            replayRetrospect(row, turns, earlier);
        } catch (RuntimeException e) {
            errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
            row.put("finalStatus", "ERROR");
        }
        row.put("ruleToday", ruleToday(allTurns).key());
        row.put("ruleHistory", pastDays.stream().map(d -> safetyPolicy.scan(d.path("diary").asString("")).level())
                .reduce(SafetyLevel.NONE, SafetyLevel::max).key());
        row.put("judge", "OFF".equals(GATE) ? null : List.copyOf(judgeOut));
        row.put("verifier", "OFF".equals(GATE) ? null : List.copyOf(verdicts));
        row.put("regenerations", Math.max(0, guardedBodies.get() - 1));
        row.put("gate", Map.of("mode", GATE, "judge", JUDGE, "verifier", VERIFIER));
        row.put("llmSafetyLevels", List.copyOf(llmLevels));
        row.put("crisisEvent", events.stream(CrisisDetected.class)
                .anyMatch(e -> e.level().atLeast(SafetyLevel.RISK)));
        row.put("model", Map.of("chat", REAL ? "gemini" : "mock", "generator", REAL ? "gemini" : "mock"));
        row.put("mocked", mocked());
        return row;
    }

    private void replayRetrospect(Map<String, Object> row, List<String> turns, List<List<String>> earlier) {
        User user = userRepository.saveAndFlush(User.create());
        seedPastDiaries(user);
        Map<String, Double> gateRowsBefore = gateRows();
        long started = System.nanoTime();
        List<Boolean> earlierHeld = new ArrayList<>();
        for (List<String> session : earlier) {
            var first = retrospectService.start(user.getId(), StartCommand.today(null, null));
            ReplyDto r = first.reply();
            for (String turn : session) {
                if (r.done() || "safety_hold".equals(r.phase())) {
                    break; // 위기 안내에서 멈추면 그대로 나간다 — 일기도 편지도 없다.
                }
                r = retrospectService.handle(user.getId(), first.id(), TurnCommand.text(turn));
            }
            earlierHeld.add("safety_hold".equals(r.phase()));
        }
        row.put("earlierSessionsHeld", earlierHeld);
        var start = retrospectService.start(user.getId(), StartCommand.today(null, null));
        Long retrospectId = start.id();
        ReplyDto reply = start.reply();
        Integer holdAt = null;
        int replayed = 0;
        for (String turn : turns) {
            if (reply.done()) {
                break;
            }
            if ("safety_hold".equals(reply.phase())) {
                // 「이어서 얘기하기」— 다음 입력은 답으로 쓰지 않고 멈추기 전 질문을 다시 보여 준다.
                reply = retrospectService.handle(user.getId(), retrospectId, TurnCommand.option("1"));
            }
            reply = retrospectService.handle(user.getId(), retrospectId, TurnCommand.text(turn));
            replayed++;
            if (holdAt == null && "safety_hold".equals(reply.phase())) {
                holdAt = replayed;
            }
        }
        // 대본이 끝났는데 회고가 열려 있으면 「그만할래요」로 일기를 정리한다(위기 뒤라면 먼저 이어서 하기).
        for (int i = 0; i < 3 && !reply.done(); i++) {
            reply = "safety_hold".equals(reply.phase())
                    ? retrospectService.handle(user.getId(), retrospectId, TurnCommand.option("1"))
                    : retrospectService.handle(user.getId(), retrospectId, TurnCommand.text(STOP));
        }
        long retroMs = (System.nanoTime() - started) / 1_000_000;
        row.put("turnsReplayed", replayed);
        row.put("turnsDropped", turns.size() - replayed);
        row.put("safetyHoldAtTurn", holdAt);
        row.put("retroPhase", reply.phase());

        List<Message> messages = codec.deserialize(
                retrospectRepository.findById(retrospectId).orElseThrow().getStateJson()).messages();
        boolean guidance = messages.stream().filter(Message::isAssistant).map(Message::content)
                .anyMatch(safetyPolicy::isGuidance);
        row.put("crisisGuidance", guidance);
        row.put("riskEvent", riskEvent(user.getId()));

        Optional<Diary> diary = diaryRepository.findByRetrospectId(retrospectId);
        if (diary.isEmpty()) {
            // 위기로 멈춘 채 끝나거나 어뷰징으로 끝난 회고 — 일기도 편지도 없다.
            row.put("policy", null);
            row.put("finalStatus", "NO_LETTER");
            row.put("delivered", Map.of("generated", false, "quote", "", "actions", 0));
            row.put("latencyMs", Map.of("retrospect", retroMs, "letter", 0));
            return;
        }
        row.put("policy", guidance ? "QUIET" : "NORMAL");
        long letterStarted = System.nanoTime();
        Duration wait = REAL ? Duration.ofSeconds(120) : Duration.ofSeconds(15);
        Letter first = await().atMost(wait).pollInterval(Duration.ofMillis(100))
                .until(() -> letterRepository.findByDiaryId(diary.get().getId()).orElse(null),
                        l -> l != null && (l.getStatus().isDone() || "FAILED".equals(l.getStatus().name())));
        Letter letter = first;
        row.put("backgroundStatus", letter.getStatus().name());
        if (editedDiary != null) {
            // 회고 뒤 일기 고치기 — 운영 리스너가 열기 전 편지를 stale 로 표시하고 고친 일기로 다시 만든다.
            diaryService.updateOriginalByRetrospect(user.getId(), retrospectId, editedDiary);
            letter = await().atMost(wait).pollInterval(Duration.ofMillis(100))
                    .until(() -> letterRepository.findById(first.getId()).orElse(null),
                            l -> !l.isStale() && (l.getStatus().isDone() || "FAILED".equals(l.getStatus().name())));
            row.put("diaryEditedAfterRetro", true);
        }
        // 다음 날 아침 열기 — 편지 날짜를 하루 당겨 「오늘 전」 편지로 만들고 열기 경로(실패면 그 자리에서 보충)를 탄다.
        jdbc.update("update letters set letter_date = letter_date - 1 where id = ?", letter.getId());
        LetterView view = letterService.current(user.getId()).orElseThrow();
        long letterMs = (System.nanoTime() - letterStarted) / 1_000_000;

        String status = view.status().name();
        boolean generated = "READY".equals(status);
        if (!"OFF".equals(GATE)) {
            LetterPolicy policy = letterRepository.findById(letter.getId()).orElseThrow().getSafetyPolicy();
            row.put("policy", policy == null ? null : policy.name());
            Map<String, Double> after = gateRows();
            row.put("row", after.entrySet().stream()
                    .filter(e -> e.getValue() > gateRowsBefore.getOrDefault(e.getKey(), 0.0))
                    .map(Map.Entry::getKey).sorted().reduce((a, b) -> a + "," + b).orElse(null));
        }
        row.put("finalStatus", status);
        Map<String, Object> delivered = new LinkedHashMap<>();
        delivered.put("generated", generated);
        delivered.put("quote", view.quote() == null ? "" : view.quote());
        delivered.put("insight", view.insight() == null ? "" : view.insight());
        delivered.put("actions", view.actions().size());
        delivered.put("helplines", view.helplines().size());
        if (datasetLetter != null) {
            String body = datasetLetter.path("body").asString("");
            String quote = datasetLetter.path("quote").asString("");
            delivered.put("letterBodyDelivered", generated && body.strip().equals(view.insight()));
            delivered.put("letterQuoteDelivered", generated && !quote.isBlank() && quote.strip().equals(view.quote()));
            delivered.put("letterQuestionDelivered", generated
                    && datasetLetter.path("firstQuestion").asString("").strip().equals(view.tonightQuestion()));
            // 작은 일은 B0 가 연결표에서 고르므로 데이터셋 편지의 작은 일은 전달되지 않는다.
            delivered.put("letterActionsDelivered", false);
        }
        row.put("delivered", delivered);
        row.put("latencyMs", Map.of("retrospect", retroMs, "letter", letterMs));
    }

    /**
     * P0(V40)의 안전 기록 표 safety_events 에 이 사용자의 RISK 이상 기록이 있는가. 표에는 RISK · IMMINENT 만 들어가고
     * 사례마다 새 사용자라 날짜 조건은 따로 걸지 않는다. B0-pre 에는 표가 없어 null 이다.
     */
    private Boolean riskEvent(Long userId) {
        Integer tables = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'safety_events'", Integer.class);
        if (tables == null || tables == 0) {
            return null;
        }
        Integer n = jdbc.queryForObject("select count(*) from safety_events where user_id = ?", Integer.class, userId);
        return n != null && n > 0;
    }

    /** 설계서 6절의 ruleToday — 오늘 일기 본문과 오늘 회고의 사용자 말 하나하나를 scan 한 최댓값. */
    private SafetyLevel ruleToday(List<String> turns) {
        SafetyLevel level = safetyPolicy.scan(editedDiary != null ? editedDiary : diaryText).level();
        for (String turn : turns) {
            level = SafetyLevel.max(level, safetyPolicy.scan(turn).level());
        }
        return level;
    }

    @SuppressWarnings("unchecked")
    private void stub() {
        doReturn(EmotionExtraction.empty()).when(extractor).extract(any());
        doAnswer(inv -> Optional.of(new DiaryOutput(diaryText, null))).when(diaryWriter).write(any());
        doAnswer(inv -> {
            Optional<DiaryTurn> turn = REAL ? (Optional<DiaryTurn>) inv.callRealMethod()
                    : Optional.of(new DiaryTurn(inv.getArgument(1), List.of(), null, false, null, null,
                            "none", List.of(), false, false, false, false, false));
            llmLevels.add(turn.map(t -> t.safetyLevelOrNone().key()).orElse("unavailable"));
            return turn;
        }).when(chat).turn(any(), any());
        doAnswer(inv -> failLetter ? Optional.empty() : datasetLetter != null ? Optional.of(datasetBody())
                : REAL ? inv.callRealMethod() : Optional.of(NEUTRAL_BODY)).when(writer).writeBody(any());
        doAnswer(inv -> failLetter ? Optional.empty() : datasetLetter != null ? Optional.of(datasetBody())
                : REAL ? inv.callRealMethod() : Optional.of(NEUTRAL_BODY)).when(writer).writeBodyOnOpen(any());
        // 안전 게이트(P1)의 본문 — 정책 · 고칠 점을 받는 판. 다시 쓰기도 같은 흉내를 돌려준다(흉내 모드는 고치지 못한다).
        doAnswer(inv -> {
            guardedBodies.incrementAndGet();
            return failLetter ? Optional.empty() : datasetLetter != null ? Optional.of(datasetBody())
                    : REAL ? inv.callRealMethod() : Optional.of(NEUTRAL_BODY);
        }).when(writer).writeBody(any(), any(), anyList());
        doAnswer(inv -> failLetter ? Optional.empty() : datasetLetter != null ? Optional.of(datasetBody())
                : REAL ? inv.callRealMethod() : Optional.of(NEUTRAL_BODY)).when(writer).writeBodyOnOpen(any(), any());
        doAnswer(inv -> {
            Optional<RiskJudgment> j = switch (JUDGE) {
                case "real" -> (Optional<RiskJudgment>) inv.callRealMethod();
                case "none" -> Optional.empty();
                default -> oracleJudgment(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
            };
            judgeOut.add(j.map(x -> x.todayCurrent().key() + "/" + x.todayMentioned().key() + "/"
                    + x.historyRisk().key() + "/" + x.trajectory().name().toLowerCase() + (x.uncertain() ? "/uncertain" : ""))
                    .orElse("none"));
            return j;
        }).when(judge).judge(any(), any(), anyList(), anyList());
        doAnswer(inv -> recordVerdict("real".equals(VERIFIER) ? (LetterVerification) inv.callRealMethod()
                : oracleVerification(inv.getArgument(2)))).when(verifier).verify(any(), any(), any());
        doAnswer(inv -> recordVerdict("real".equals(VERIFIER) ? (LetterVerification) inv.callRealMethod()
                : oracleVerification(inv.getArgument(2)))).when(verifier).verifyOnOpen(any(), any(), any());
        if (!REAL) {
            doReturn(List.of()).when(writer).pickNeeds(any());
            doReturn(List.of()).when(writer).pickNeedsOnOpen(any());
            doReturn(Optional.empty()).when(writer).pickActionIds(any(), anyList());
            doReturn(Optional.empty()).when(writer).pickActionIdsOnOpen(any(), anyList());
        }
    }

    // ── 안전 게이트(P1) 재생 도우미 ───────────────────────────────────────

    /** 여러 날 사례의 앞 날 일기를 그날 날짜로 넣는다 — 판정자가 지난 기록을 본다(B0 는 읽지 않는다). */
    private void seedPastDiaries(User user) {
        int n = pastDays.size();
        for (int i = 0; i < n; i++) {
            String text = pastDays.get(i).path("diary").asString("");
            if (text.isBlank()) {
                continue;
            }
            var retro = retrospectRepository.save(com.momentory.retrospect.infrastructure.persistence.Retrospect.start(
                    user.getId(), com.momentory.retrospect.domain.RetrospectStatus.COMPLETED, null, "{}"));
            Diary d = diaryRepository.saveAndFlush(Diary.create(user.getId(), retro.getId(), text, null, null));
            java.sql.Timestamp at = java.sql.Timestamp.from(com.momentory.common.time.DayBoundary.startOfDay(
                    com.momentory.common.time.DayBoundary.today().minusDays(n - i)).plusSeconds(3600));
            jdbc.update("update diaries set created_at = ? where id = ?", at, d.getId());
            jdbc.update("update retrospects set created_at = ? where id = ?", at, retro.getId());
        }
    }

    /**
     * 정답 판정자 — 데이터셋 제안 라벨의 세 칸을 판정으로 돌려준다(판정자가 완벽할 때의 상한). 근거 문구는 원문 그대로인
     * 첫 사용자 말 · 일기 · 지난 일기에서 고른다. 지난 일기가 없는데 historyRisk 가 있으면 근거를 댈 수 없어 쓰지 못한다.
     */
    private Optional<RiskJudgment> oracleJudgment(String diary, List<String> words,
            List<GeminiRiskJudge.PastDiary> past) {
        JsonNode p = baseProposed;
        if (p == null || p.isMissingNode()) {
            return Optional.empty();
        }
        SafetyLevel current = SafetyLevel.fromKey(p.path("todayCurrent").asString("none"));
        SafetyLevel mentioned = SafetyLevel.fromKey(p.path("todayMentioned").asString("none"));
        SafetyLevel history = SafetyLevel.fromKey(p.path("historyRisk").asString("none"));
        RiskJudgment.Trajectory trajectory;
        try {
            trajectory = RiskJudgment.Trajectory.valueOf(p.path("trajectory").asString("UNKNOWN").toUpperCase());
        } catch (IllegalArgumentException e) {
            trajectory = RiskJudgment.Trajectory.UNKNOWN;
        }
        String today = words.stream().filter(w -> w != null && !w.isBlank()).findFirst().orElse(diary);
        List<RiskJudgment.Evidence> evidence = new ArrayList<>();
        evidence.add(new RiskJudgment.Evidence("todayCurrent", RiskJudgment.Source.TODAY, today));
        evidence.add(new RiskJudgment.Evidence("todayMentioned", RiskJudgment.Source.TODAY, today));
        if (!past.isEmpty()) {
            evidence.add(new RiskJudgment.Evidence("historyRisk", RiskJudgment.Source.PAST, past.get(0).text()));
        }
        boolean uncertain = "uncertain".equals(p.path("class").asString(""));
        return Optional.of(new RiskJudgment(current, mentioned, history, trajectory, uncertain, evidence));
    }

    /** 정답 검증기 — 데이터셋 편지(L)의 위반 라벨 가운데 지금 편지에 글자 그대로 남아 있는 것만 위반으로 돌려준다. */
    private LetterVerification oracleVerification(GeminiLetterVerifier.Draft draft) {
        if ("pass".equals(VERIFIER) || letterProposed == null) {
            return LetterVerification.of(List.of());
        }
        List<LetterVerification.Violation> found = new ArrayList<>();
        for (JsonNode v : letterProposed.path("violations")) {
            String location = v.path("location").asString("body");
            LetterVerification.Target target = switch (location) {
                case "quote" -> LetterVerification.Target.QUOTE;
                case "firstQuestion" -> LetterVerification.Target.TONIGHT_QUESTION;
                case "actionTitle" -> LetterVerification.Target.ACTION_TITLE;
                default -> LetterVerification.Target.INSIGHT;
            };
            String text = switch (target) {
                case QUOTE -> draft.quote();
                case TONIGHT_QUESTION -> draft.tonightQuestion();
                case ACTION_TITLE -> String.join("\n", draft.actionTitles());
                default -> draft.insight();
            };
            String evidence = v.path("evidence").asString("");
            if (text != null && !evidence.isBlank()
                    && com.momentory.letter.domain.LetterRules.isVerbatim(evidence, List.of(text))) {
                found.add(new LetterVerification.Violation(v.path("rule").asString("R3"),
                        "major".equals(v.path("severity").asString("")), target, evidence, "그 부분을 빼고 다시 쓴다"));
            }
        }
        return LetterVerification.of(found);
    }

    private LetterVerification recordVerdict(LetterVerification v) {
        verdicts.add(v.outcome().name() + "(" + v.rules() + ")");
        return v;
    }

    /** 결정표 줄별 누적 건수 — 사례 전후 차이로 이 사례가 지난 줄을 찾는다. */
    private Map<String, Double> gateRows() {
        Map<String, Double> rows = new LinkedHashMap<>();
        meters.find("letter.gate").counters()
                .forEach(c -> rows.merge(c.getId().getTag("row"), c.count(), Double::sum));
        return rows;
    }

    private Body datasetBody() {
        return new Body(datasetLetter.path("quote").asString(""), datasetLetter.path("body").asString(""),
                datasetLetter.path("firstQuestion").asString(""));
    }

    private List<String> mocked() {
        List<String> m = new ArrayList<>(List.of(
                "emotionExtractor=empty(감정 탐색 생략 · 편지 events 빈 목록)",
                "diaryWriter=dataset diary 원문(고친 사례는 diaryBeforeEdit 뒤 diary 로 고침)",
                "explorationTurns=재생 안 함(B0 는 감정 탐색 단계를 scan 하지 않음)",
                "clock=오늘 회고로 재생 · 편지는 하루 당겨 열기 경로로 확인",
                "multiDay=마지막 날만 재생"));
        if (!REAL) {
            m.add("diaryChat=safetyLevel none 고정(규칙 층만 위기를 잡음) · 질문은 엔진 기본 문구");
            m.add("letterNeeds=빈 목록(기본 욕구 · 연결표 순서 작은 일)");
        }
        m.add(failLetter ? "letterBody=생성 실패 흉내(뒤에서 · 열 때 모두)" : datasetLetter != null ? "letterBody=데이터셋 편지(checkedBody 는 운영 그대로)"
                : REAL ? "letterBody=Gemini" : "letterBody=고정 중립 본문");
        return m;
    }

    /** chatTurns 원소는 사용자 말 문자열이거나 {role|speaker, text|content} 객체다. 사용자 말만 쓴다. */
    private static List<String> userTurns(JsonNode chatTurns) {
        List<String> turns = new ArrayList<>();
        for (JsonNode t : chatTurns) {
            if (t.isString()) {
                turns.add(t.asString());
                continue;
            }
            String role = t.path("role").asString(t.path("speaker").asString("user"));
            if (role.equals("user")) {
                turns.add(t.path("text").asString(t.path("content").asString("")));
            }
        }
        return turns;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static String required(String key) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("-P" + key + "=... 가 필요하다");
        }
        return v;
    }

    private static String git(String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String outText = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return p.waitFor() == 0 ? outText : "unknown";
        } catch (IOException | InterruptedException e) {
            return "unknown";
        }
    }
}
