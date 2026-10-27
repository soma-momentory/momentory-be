package com.momentory.letter.application;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.momentory.letter.domain.Letter;
import com.momentory.letter.domain.LetterAction;
import com.momentory.letter.domain.LetterActionSource;
import com.momentory.letter.domain.LetterMaterials;
import com.momentory.letter.domain.LetterRules;
import com.momentory.letter.domain.LetterStatus;
import com.momentory.letter.domain.NeedsSource;
import com.momentory.letter.infrastructure.LetterActionRepository;
import com.momentory.letter.infrastructure.LetterRepository;
import com.momentory.letter.infrastructure.ai.GeminiLetterWriter;
import com.momentory.retrospect.domain.Need;
import com.momentory.retrospect.domain.NeedActionCatalog;
import com.momentory.retrospect.domain.Needs;

/**
 * 편지를 만든다 — 회고 커밋 뒤(뒤에서)와 열 때 보충(그 자리에서) 두 길(PRD 6-1).
 *
 * <p>Gemini 호출은 트랜잭션 밖에서 한다. 들어갈 때는 저장소의 조건부 갱신({@code claim})으로 한쪽만
 * 들어오고, 다 만든 내용은 짧은 트랜잭션 하나로 저장한다.
 *
 * <ul>
 *   <li>뒤에서: 편지 본문(30초 + 1회 재시도) → 욕구(바람카드 것 또는 AI 추정) → 작은 일 번호. 본문이
 *       실패하면 FAILED 로 두고 열 때 다시 만든다.</li>
 *   <li>열 때: 본문과 「욕구 → 작은 일 번호」를 동시에, 호출마다 8초 한 번. 본문이 실패하면 기본 편지(FALLBACK)를
 *       저장한다.</li>
 *   <li>회고 중 위기 안내가 나왔으면 두 길 모두 생성 모델을 부르지 않고 조용한 편지(QUIET)로 끝낸다.</li>
 * </ul>
 */
@Component
public class LetterGenerator {

    private static final Logger log = LoggerFactory.getLogger(LetterGenerator.class);

    static final String PROMPT_VERSION = "v1";
    /**
     * 이보다 오래 GENERATING 이면 멈춘 것으로 보고(서버 재시작 등) 다시 들어간다(PRD 6-1). 뒤에서
     * 만들기는 Gemini 가 느린 날 최악 3분쯤 걸려 이 기준을 넘길 수 있다. 그래도 늦게 끝난 쪽은
     * {@link #ownedForUpdate} 에 막혀 저장하지 못하므로 데이터는 안전하고, 대가는 호출이 한 번 더 나가는 것이다.
     */
    static final Duration STUCK_AFTER = Duration.ofSeconds(60);
    /** 열 때 다른 쪽이 만드는 중이면 이만큼 기다린다. */
    static final Duration OPEN_WAIT = Duration.ofSeconds(8);
    private static final Duration POLL = Duration.ofMillis(300);
    private static final int SLOTS = 3;
    private static final Need DEFAULT_NEED = Needs.byWord("휴식").orElseThrow();
    /** 열 때 욕구 고르기를 본문과 동시에 돌린다 — 기다리기만 하는 호출이라 가상 스레드면 충분하다. */
    private static final ExecutorService OPEN_CALLS = Executors.newVirtualThreadPerTaskExecutor();

    private final LetterRepository letterRepository;
    private final LetterActionRepository letterActionRepository;
    private final LetterMaterialsReader materialsReader;
    private final GeminiLetterWriter writer;
    private final NeedActionCatalog catalog;
    private final TransactionTemplate transaction;
    private final MeterRegistry meters;

    public LetterGenerator(LetterRepository letterRepository,
            LetterActionRepository letterActionRepository, LetterMaterialsReader materialsReader,
            GeminiLetterWriter writer, NeedActionCatalog catalog, TransactionTemplate transaction,
            MeterRegistry meters) {
        this.letterRepository = letterRepository;
        this.letterActionRepository = letterActionRepository;
        this.materialsReader = materialsReader;
        this.writer = writer;
        this.catalog = catalog;
        this.transaction = transaction;
        this.meters = meters;
    }

    /** 다 만든 편지 내용 — 저장 전 한 벌. */
    record Draft(LetterStatus status, String quote, String insight, String tonightQuestion,
            List<Need> needs, NeedsSource needsSource, List<ActionDraft> actions) {
    }

    record ActionDraft(String actionId, String title, LetterActionSource source, boolean chosen) {
    }

    // ── 뒤에서 ───────────────────────────────────────────────────────────

    /** 회고 커밋 뒤 · 일기를 고친 뒤 편지 실행기에서 부른다. */
    public void generateForDiary(Long diaryId) {
        letterRepository.findByDiaryId(diaryId).ifPresent(this::generateInBackground);
    }

    private void generateInBackground(Letter letter) {
        Optional<Instant> claimedAt = claim(letter.getId());
        if (claimedAt.isEmpty()) {
            return;
        }
        long startedAt = System.nanoTime();
        Optional<Draft> draft;
        boolean saved;
        try {
            Optional<LetterMaterials> materials = materialsReader.read(letter);
            if (materials.isEmpty()) {
                return; // 만드는 사이 일기가 지워졌다 — 편지도 함께 지워졌다.
            }
            LetterMaterials m = materials.get();
            draft = m.crisis() ? Optional.of(quiet(m)) : fullDraft(m);
            saved = draft.isPresent() ? save(letter.getId(), claimedAt.get(), draft.get())
                    : failIfOwner(letter.getId(), claimedAt.get());
        } catch (RuntimeException e) {
            failIfOwner(letter.getId(), claimedAt.get());
            throw e;
        }
        record("background", saved ? draft.map(d -> d.status().name()).orElse("FAILED") : "SKIPPED",
                letter.getId(), startedAt);
        // 만드는 사이 일기를 또 고쳤으면(그때 다시 만들기는 GENERATING 에 막혔다) 고친 일기로 한 번 더 만든다.
        letterRepository.findById(letter.getId())
                .filter(l -> l.isStale() && l.getOpenedAt() == null && l.getStatus().isDone())
                .ifPresent(this::generateInBackground);
    }

    private Optional<Draft> fullDraft(LetterMaterials m) {
        Optional<Draft> body = writer.writeBody(m).flatMap(b -> checkedBody(b, m));
        if (body.isEmpty()) {
            return Optional.empty();
        }
        List<Need> needs;
        NeedsSource source;
        if (!m.wishNeeds().isEmpty()) {
            needs = m.wishNeeds();
            source = NeedsSource.WISH_CARD;
        } else {
            needs = writer.pickNeeds(m);
            source = needs.isEmpty() ? NeedsSource.DEFAULT : NeedsSource.INFERRED;
        }
        if (needs.isEmpty()) {
            needs = List.of(DEFAULT_NEED);
        }
        boolean askAi = source != NeedsSource.DEFAULT;
        return Optional.of(withActions(body.get(), m, needs, source, askAi,
                askAi ? writer.pickActionIds(m, needs) : Optional.empty()));
    }

    // ── 열 때 ────────────────────────────────────────────────────────────

    /**
     * 열 때 보충 — 그대로 보여 줄 수 있으면 아무것도 안 하고 empty. 그 자리에서 만들었으면 저장한
     * 뒤 empty. 다른 쪽이 만드는 중이라 기다려도 끝나지 않았으면, 저장하지 않은 기본 편지(위기면
     * 조용한 편지)를 돌려준다 — 다른 쪽이 곧 진짜 편지를 저장한다.
     */
    Optional<Draft> ensureShowable(Letter letter) {
        if (letter.isShowable()) {
            return Optional.empty();
        }
        Optional<Instant> claimedAt = claim(letter.getId());
        if (claimedAt.isEmpty()) {
            if (waitWhileGenerating(letter.getId())) {
                return Optional.empty();
            }
            // 다른 쪽이 실패로 끝났으면 기다림을 멈추고 그 자리에서 이어받는다.
            claimedAt = claim(letter.getId());
            if (claimedAt.isEmpty()) {
                return materialsReader.read(letter).map(m -> m.crisis() ? quiet(m) : fallback(m));
            }
        }
        long startedAt = System.nanoTime();
        Draft draft;
        boolean saved;
        try {
            Optional<LetterMaterials> materials = materialsReader.read(letter);
            if (materials.isEmpty()) {
                return Optional.empty();
            }
            LetterMaterials m = materials.get();
            draft = m.crisis() ? quiet(m) : openDraft(m);
            saved = save(letter.getId(), claimedAt.get(), draft);
        } catch (RuntimeException e) {
            // 들어간 채 예외로 끝나면 60초 동안 GENERATING 에 묶인다 — 실패로 돌려 다음 요청이 바로 다시 만들게 한다.
            failIfOwner(letter.getId(), claimedAt.get());
            throw e;
        }
        record("open", saved ? draft.status().name() : "SKIPPED", letter.getId(), startedAt);
        return Optional.empty();
    }

    /**
     * 열 때 만드는 편지 — 본문(8초 한 번)과 「욕구 고르기 → 작은 일 번호 고르기」(각 8초 한 번)를 동시에
     * 돌린다. 뒤의 둘은 앞의 결과가 있어야 해서 차례로 돈다. 욕구까지 실패하면 「휴식」으로 두고 번호는
     * 고르지 않는다. 본문이 실패하면 기본 편지가 같은 작은 일을 쓴다.
     */
    private Draft openDraft(LetterMaterials m) {
        CompletableFuture<OpenPick> pick = CompletableFuture
                .supplyAsync(() -> openPick(m), OPEN_CALLS)
                // 「욕구 → 번호」는 차례로 돌아 각 8초면 16초까지 갈 수 있다. 본문과 같은 8초에서 끊고 「휴식」으로 채운다.
                .completeOnTimeout(DEFAULT_PICK, OPEN_WAIT.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(e -> DEFAULT_PICK);
        Optional<Draft> body = writer.writeBodyOnOpen(m).flatMap(b -> checkedBody(b, m));
        OpenPick p = pick.join();
        return withActions(body.orElseGet(() -> fallbackBody(m)), m, p.needs(), p.source(),
                p.source() != NeedsSource.DEFAULT, p.ids());
    }

    private record OpenPick(List<Need> needs, NeedsSource source, Optional<List<String>> ids) {
    }

    private static final OpenPick DEFAULT_PICK = new OpenPick(List.of(DEFAULT_NEED), NeedsSource.DEFAULT, Optional.empty());

    private OpenPick openPick(LetterMaterials m) {
        if (!m.wishNeeds().isEmpty()) {
            return new OpenPick(m.wishNeeds(), NeedsSource.WISH_CARD, writer.pickActionIdsOnOpen(m, m.wishNeeds()));
        }
        List<Need> inferred = writer.pickNeedsOnOpen(m);
        return inferred.isEmpty()
                ? DEFAULT_PICK
                : new OpenPick(inferred, NeedsSource.INFERRED, writer.pickActionIdsOnOpen(m, inferred));
    }

    /**
     * 다른 쪽이 만드는 동안 최대 8초 기다린다. 보여 줄 수 있게 됐으면(또는 일기가 지워졌으면) true,
     * 시간이 다 됐거나 다른 쪽이 GENERATING 을 벗어났는데 보여 줄 수 없으면(실패) false.
     */
    private boolean waitWhileGenerating(Long letterId) {
        long deadline = System.nanoTime() + OPEN_WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            Optional<Letter> current = letterRepository.findById(letterId);
            if (current.isEmpty() || current.get().isShowable()) {
                return true;
            }
            if (current.get().getStatus() != LetterStatus.GENERATING) {
                return false;
            }
        }
        return false;
    }

    // ── 편지 한 벌 만들기 ─────────────────────────────────────────────────

    /** AI 본문을 검사한다 — 관점이 비거나 너무 길면 실패, 지어낸 인용은 그 줄만 뺀다. */
    private Optional<Draft> checkedBody(GeminiLetterWriter.Body body, LetterMaterials m) {
        String insight = LetterRules.withoutGreeting(body.insight(), m.nickname());
        if (insight.isEmpty() || insight.length() > LetterRules.INSIGHT_MAX_LENGTH) {
            return Optional.empty();
        }
        String quote = body.quote() == null ? null : body.quote().strip();
        if (quote != null && (quote.length() > LetterRules.AI_QUOTE_MAX_LENGTH
                || !LetterRules.isVerbatim(quote, m.userWords()))) {
            if (!quote.isEmpty()) {
                log.info("letter quote dropped letterId={} reason=not_verbatim", m.letterId());
                meters.counter("letter.quote.dropped").increment();
            }
            quote = null;
        }
        if (quote != null) {
            quote = LetterRules.withoutLeadingTimeWord(quote);
            quote = quote.isEmpty() ? null : quote;
        }
        String question = body.tonightQuestion() == null ? "" : body.tonightQuestion().strip();
        if (question.length() > LetterRules.TONIGHT_QUESTION_MAX_LENGTH || !LetterRules.isTonightForm(question)) {
            question = LetterRules.DEFAULT_TONIGHT_QUESTION;
        }
        return Optional.of(new Draft(LetterStatus.READY, quote, insight, question, List.of(), null, List.of()));
    }

    /**
     * 작은 일 세 칸 — AI 가 고른 연결표 행동 → 연결표 순서대로 채우기.
     * {@code aiAsked} 가 거짓이면(욕구를 모를 때 · 기본 편지) AI 고르기 없이 대체 규칙만 쓴다.
     * {@code aiIds} 가 비어 있으면 AI 고르기가 실패한 것이다(「알맞은 후보 없음」은 빈 목록).
     */
    private Draft withActions(Draft body, LetterMaterials m, List<Need> needs, NeedsSource source,
            boolean aiAsked, Optional<List<String>> aiIds) {
        List<ActionDraft> slots = new ArrayList<>();
        String fallbackReason = "no_ai"; // 욕구를 모를 때 · 기다리다 보여 주는 기본 편지는 처음부터 대체 규칙이다.
        if (aiAsked) {
            Optional<List<String>> ids = aiIds;
            List<NeedActionCatalog.Action> picked = ids.map(i -> catalog.selectedActions(needs, i))
                    .orElse(List.of());
            fallbackReason = ids.isEmpty() ? "ai_failed"
                    : !ids.get().isEmpty() && picked.isEmpty() ? "rejected" : "short";
            if ("rejected".equals(fallbackReason)) {
                log.info("letter actions rejected letterId={} reason=out_of_catalog", m.letterId());
            }
            for (NeedActionCatalog.Action action : picked) {
                addIfRoom(slots, action, LetterActionSource.CATALOG);
            }
        }
        int beforeFallback = slots.size();
        for (NeedActionCatalog.Action action : catalog.candidatesFor(needs)) {
            addIfRoom(slots, action, LetterActionSource.FALLBACK);
        }
        if (slots.size() > beforeFallback) {
            meters.counter("letter.actions.fallback", "reason", fallbackReason).increment();
        }
        return new Draft(body.status(), body.quote(), body.insight(), body.tonightQuestion(), needs,
                source, slots);
    }

    private static void addIfRoom(List<ActionDraft> slots, NeedActionCatalog.Action action,
            LetterActionSource source) {
        boolean used = slots.stream().anyMatch(s -> action.id().equals(s.actionId())
                || action.title().equals(s.title()));
        if (slots.size() < SLOTS && !used) {
            slots.add(new ActionDraft(action.id(), action.title(), source, false));
        }
    }

    /**
     * 기본 편지 — 다른 쪽이 만드는 중이라 이번에만 보여 줄 때. AI 를 더 부르지 않는다(이미 8초를 기다렸다).
     * 평소 편지와 같은 틀로 보인다.
     */
    private Draft fallback(LetterMaterials m) {
        return withActions(fallbackBody(m), m, quickNeeds(m), quickSource(m), false, Optional.empty());
    }

    private static Draft fallbackBody(LetterMaterials m) {
        return new Draft(LetterStatus.FALLBACK, LetterRules.fallbackQuote(m.diary()),
                LetterRules.defaultBody(m.letterDate()), LetterRules.DEFAULT_TONIGHT_QUESTION,
                List.of(), null, List.of());
    }

    /** 조용한 편지 — 인용 · 작은 일 · 첫 질문 예고가 없다. */
    private static Draft quiet(LetterMaterials m) {
        return new Draft(LetterStatus.QUIET, null, LetterRules.quietBody(), null,
                List.of(), null, List.of());
    }

    private static List<Need> quickNeeds(LetterMaterials m) {
        return m.wishNeeds().isEmpty() ? List.of(DEFAULT_NEED) : m.wishNeeds();
    }

    private static NeedsSource quickSource(LetterMaterials m) {
        return m.wishNeeds().isEmpty() ? NeedsSource.DEFAULT : NeedsSource.WISH_CARD;
    }

    // ── 저장 ─────────────────────────────────────────────────────────────

    /** 들어갔으면 그 시각을 준다 — 저장할 때 「내가 들어간 그 시도인가」를 확인하는 열쇠다. */
    private Optional<Instant> claim(Long letterId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS); // timestamptz 정밀도와 맞춘다
        return letterRepository.claim(letterId, now, now.minus(STUCK_AFTER)) == 1 ? Optional.of(now) : Optional.empty();
    }

    /**
     * 내가 들어간 시도일 때만 행을 잠그고 돌려준다. 오래 걸려 다른 쪽이 가로챘으면(STUCK_AFTER) 늦게
     * 끝난 쪽은 저장하지 않는다 — 먼저 끝난 편지 · 사용자가 고른 작은 일을 덮어쓰지 않게.
     */
    private Optional<Letter> ownedForUpdate(Long letterId, Instant claimedAt) {
        Optional<Letter> owned = letterRepository.findForUpdate(letterId)
                .filter(l -> l.getStatus() == LetterStatus.GENERATING && claimedAt.equals(l.getGenerationStartedAt()));
        if (owned.isEmpty()) {
            log.info("letter save skipped letterId={} reason=not_owner", letterId);
        }
        return owned;
    }

    /** 내 시도면 실패로 돌린다. 돌렸으면 true. */
    private boolean failIfOwner(Long letterId, Instant claimedAt) {
        return Boolean.TRUE.equals(transaction.execute(status -> ownedForUpdate(letterId, claimedAt)
                .map(letter -> {
                    letter.fail();
                    return true;
                }).orElse(false)));
    }

    /** 내 시도일 때만 저장한다. 저장했으면 true — 다른 쪽이 가로챘으면 false. */
    private boolean save(Long letterId, Instant claimedAt, Draft draft) {
        return Boolean.TRUE.equals(transaction.execute(status -> ownedForUpdate(letterId, claimedAt).map(letter -> {
            Set<String> words = draft.needs().stream().map(Need::word)
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            letter.complete(draft.status(), draft.quote(), draft.insight(), draft.tonightQuestion(),
                    words.isEmpty() ? null : String.join(",", words), draft.needsSource(), PROMPT_VERSION,
                    Instant.now());
            letterActionRepository.deleteByLetterId(letterId);
            for (int i = 0; i < draft.actions().size(); i++) {
                ActionDraft a = draft.actions().get(i);
                letterActionRepository.save(LetterAction.create(letterId, i, a.actionId(), a.title(),
                        a.source(), a.chosen()));
            }
            return true;
        }).orElse(false)));
    }

    /** 생성 결과별 건수 · 지연(PRD 10-2) — 경로(뒤에서 · 열 때)를 나눠 열 때 보충 비율도 본다. */
    private void record(String path, String status, Long letterId, long startedAtNanos) {
        long elapsedNanos = System.nanoTime() - startedAtNanos;
        meters.timer("letter.generation", "path", path, "status", status)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
        log.info("letter generated path={} letterId={} status={} latencyMs={}", path, letterId, status,
                elapsedNanos / 1_000_000L);
    }
}
