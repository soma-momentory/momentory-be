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
import com.momentory.letter.domain.LetterPolicy;
import com.momentory.letter.domain.LetterRules;
import com.momentory.letter.domain.LetterStatus;
import com.momentory.letter.domain.NeedsSource;
import com.momentory.letter.infrastructure.LetterActionRepository;
import com.momentory.letter.infrastructure.LetterRepository;
import com.momentory.letter.infrastructure.ai.GeminiLetterVerifier;
import com.momentory.letter.infrastructure.ai.GeminiLetterWriter;
import com.momentory.retrospect.domain.Need;
import com.momentory.retrospect.domain.NeedActionCatalog;
import com.momentory.retrospect.domain.Needs;
import com.momentory.retrospect.domain.safety.SafetyLevel;
import com.momentory.retrospect.domain.safety.SafetyPolicy;

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

    static final String PROMPT_VERSION = "v2";
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
    private final SafetyPolicy safetyPolicy;
    private final LetterGateProperties gate;
    private final LetterSafetyAssessor assessor;
    private final GeminiLetterVerifier verifier;

    public LetterGenerator(LetterRepository letterRepository,
            LetterActionRepository letterActionRepository, LetterMaterialsReader materialsReader,
            GeminiLetterWriter writer, NeedActionCatalog catalog, TransactionTemplate transaction,
            MeterRegistry meters, SafetyPolicy safetyPolicy, LetterGateProperties gate,
            LetterSafetyAssessor assessor, GeminiLetterVerifier verifier) {
        this.letterRepository = letterRepository;
        this.letterActionRepository = letterActionRepository;
        this.materialsReader = materialsReader;
        this.writer = writer;
        this.catalog = catalog;
        this.transaction = transaction;
        this.meters = meters;
        this.safetyPolicy = safetyPolicy;
        this.gate = gate;
        this.assessor = assessor;
        this.verifier = verifier;
    }

    /** 다 만든 편지 내용 — 저장 전 한 벌. */
    record Draft(LetterStatus status, String quote, String insight, String tonightQuestion,
            List<Need> needs, NeedsSource needsSource, List<ActionDraft> actions, LetterPolicy policy) {

        Draft withActions(List<ActionDraft> replaced) {
            return new Draft(status, quote, insight, tonightQuestion, needs, needsSource, replaced, policy);
        }
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
            draft = switch (gate.mode()) {
                case OFF -> m.crisis() ? Optional.of(quiet(m)) : fullDraft(m);
                case SHADOW -> shadowDraft(letter, m);
                case ON -> guardedDraft(letter, claimedAt.get(), m);
            };
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

    // ── 안전 게이트(설계서 5 · 6절) ────────────────────────────────────────

    /**
     * 게이트 ON · 뒤에서 — 결정표로 정책을 고르고, 그 정책으로 쓰고, 전달 전에 검증해 닫는다. 정책은 본문을 쓰기
     * 전에 먼저 남긴다 — 본문이 실패해 FAILED 가 되어도 열 때 보충이 판정을 다시 부르지 않고 같은 정책을 쓴다.
     */
    private Optional<Draft> guardedDraft(Letter letter, Instant claimedAt, LetterMaterials m) {
        LetterSafetyAssessor.Assessment a = assessor.assess(letter, m, true);
        LetterPolicy policy = a.policy();
        if (policy == LetterPolicy.QUIET) {
            logGate("on", m, a, "QUIET", "-", 0);
            return Optional.of(quiet(m, LetterPolicy.QUIET));
        }
        recordPolicyIfOwner(letter.getId(), claimedAt, policy);
        Optional<Draft> draft = fullDraft(m, policy, List.of());
        if (draft.isEmpty()) {
            logGate("on", m, a, "FAILED", "-", 0);
            return Optional.empty();
        }
        Closed closed = gate.ablation().verify() ? close(m, policy, draft.get(), true)
                : new Closed(draft.get(), "SKIPPED", 0); // 비교 실험 P − 검증기
        logGate("on", m, a, closed.draft().status().name(), closed.verdict(), closed.regenerations());
        return Optional.of(closed.draft());
    }

    /**
     * 게이트 SHADOW — 판정 · 검증을 돌려 기록만 하고, 사용자에게는 지금 경로의 편지를 그대로 보낸다(V6).
     * 실제 일기를 판정자에 한 번 더 보내므로 켜기 전에 처리방침 범위를 확인한다(설계서 5.9절).
     */
    private Optional<Draft> shadowDraft(Letter letter, LetterMaterials m) {
        LetterSafetyAssessor.Assessment a = assessor.assess(letter, m, true);
        Optional<Draft> current = m.crisis() ? Optional.of(quiet(m)) : fullDraft(m);
        String verdict = "-";
        if (current.isPresent() && current.get().status() == LetterStatus.READY) {
            verdict = verifier.verify(m, LetterPolicy.NORMAL, verifierDraft(current.get())).outcome().name();
        }
        logGate("shadow", m, a, current.map(d -> d.status().name()).orElse("FAILED"), verdict, 0);
        return current;
    }

    /** 게이트 ON · 열 때 보충 — 판정자를 부르지 않는다(8초). 뒤에서 남긴 정책이 있으면 그것을 쓴다. */
    private Draft guardedOpenDraft(Letter letter, LetterMaterials m) {
        LetterPolicy policy = openPolicy(letter, m);
        if (policy == LetterPolicy.QUIET) {
            return quiet(m, LetterPolicy.QUIET);
        }
        CompletableFuture<OpenPick> pick = CompletableFuture
                .supplyAsync(() -> openPick(m), OPEN_CALLS)
                .completeOnTimeout(DEFAULT_PICK, OPEN_WAIT.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(e -> DEFAULT_PICK);
        Optional<Draft> body = writer.writeBodyOnOpen(m, policy).flatMap(b -> checkedBody(b, m))
                .map(b -> applyPolicy(b, policy));
        OpenPick p = pick.join();
        if (body.isEmpty()) { // 본문 실패 — AI 글이 없으니 검증할 것도 없다. 기본 편지로.
            return withActions(applyPolicy(fallbackBody(m), policy), m, p.needs(), p.source(),
                    p.source() != NeedsSource.DEFAULT, p.ids());
        }
        Draft draft = withActions(body.get(), m, p.needs(), p.source(), p.source() != NeedsSource.DEFAULT, p.ids());
        return gate.ablation().verify() ? close(m, policy, draft, false).draft() : draft;
    }

    /** 게이트 ON · 다른 쪽이 만드는 중이라 이번에만 보여 주는 편지. */
    private Draft unsavedDraft(Letter letter, LetterMaterials m) {
        if (gate.mode() != LetterGateProperties.Mode.ON) {
            return m.crisis() ? quiet(m) : fallback(m);
        }
        LetterPolicy policy = openPolicy(letter, m);
        return policy == LetterPolicy.QUIET ? quiet(m, LetterPolicy.QUIET)
                : withActions(applyPolicy(fallbackBody(m), policy), m, quickNeeds(m), quickSource(m), false,
                        Optional.empty());
    }

    private LetterPolicy openPolicy(Letter letter, LetterMaterials m) {
        if (m.crisis()) { // 안전 기록은 뒤에서 남긴 정책보다 늦게 생겼을 수 있다(P0-2 · P0-3)
            return LetterPolicy.QUIET;
        }
        LetterPolicy stored = letter.getSafetyPolicy();
        return stored != null ? stored : assessor.assess(letter, m, false).policy();
    }

    private Optional<Draft> fullDraft(LetterMaterials m, LetterPolicy policy, List<String> fixes) {
        Optional<Draft> body = writer.writeBody(m, policy, fixes).flatMap(b -> checkedBody(b, m))
                .map(b -> applyPolicy(b, policy));
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

    /** 정책을 본문에 새긴다 — GENTLE 은 인용 없이 기본 첫 질문(모델이 지시를 어겨도 서버가 지킨다). */
    private static Draft applyPolicy(Draft body, LetterPolicy policy) {
        boolean gentle = policy == LetterPolicy.GENTLE;
        return new Draft(body.status(), gentle ? null : body.quote(), body.insight(),
                gentle ? LetterRules.DEFAULT_TONIGHT_QUESTION : body.tonightQuestion(), body.needs(),
                body.needsSource(), body.actions(), policy);
    }

    record Closed(Draft draft, String verdict, int regenerations) {
    }

    /**
     * 생성 뒤 검증과 닫기(설계서 6.4절). 검증되지 않은 편지는 그대로 나가지 않고, 다시 쓰기는 한 번뿐이며, 그다음은
     * 인용 없는 고정 기본 편지다. 설계서와 다른 점: 위반이 작은 일 맞춤 제목에만 있으면 본문을 다시 쓰지 않고
     * 정본 제목으로 바꿔 닫는다 — 정본 제목은 연결표에서 검토를 마친 문장이라 다시 쓰기보다 싸고 확실하다.
     */
    private Closed close(LetterMaterials m, LetterPolicy policy, Draft draft, boolean background) {
        LetterVerification v = verify(m, policy, draft, background);
        if (background && v.outcome() == LetterVerification.Outcome.UNUSABLE) {
            pause(); // V4 — 문제가 편지가 아니라 검증 호출에 있으니 검증만 한 번 더
            v = verify(m, policy, draft, true);
        }
        String verdict = v.outcome().name() + "(" + v.rules() + ")";
        switch (v.outcome()) {
            case PASS:
                return new Closed(draft, verdict, 0);
            case UNUSABLE:
                return new Closed(fixedFallback(m, policy, draft), verdict, 0);
            default:
                break;
        }
        if (v.closesQuiet()) { // V2 — 게이트가 위험을 놓쳤다는 신호
            return new Closed(quiet(m, LetterPolicy.QUIET), verdict, 0);
        }
        if (v.onlyActionTitles()) {
            return new Closed(canonicalTitles(draft), verdict, 0);
        }
        if (!background) { // 열 때는 다시 쓸 시간이 없다
            return new Closed(fixedFallback(m, policy, draft), verdict, 0);
        }
        List<String> fixes = v.violations().stream()
                .map(x -> x.rule() + ": " + (x.fix() == null || x.fix().isBlank() ? "그 부분을 빼고 다시 씁니다" : x.fix()))
                .distinct().toList();
        boolean titlesFlagged = v.violations().stream()
                .anyMatch(x -> x.target() == LetterVerification.Target.ACTION_TITLE);
        Optional<Draft> body = writer.writeBody(m, policy, fixes).flatMap(b -> checkedBody(b, m))
                .map(b -> applyPolicy(b, policy));
        if (body.isEmpty()) {
            return new Closed(fixedFallback(m, policy, draft), verdict + ">regen_failed", 1);
        }
        Draft second = new Draft(LetterStatus.READY, body.get().quote(), body.get().insight(),
                body.get().tonightQuestion(), draft.needs(), draft.needsSource(), draft.actions(), policy);
        if (titlesFlagged) {
            second = canonicalTitles(second);
        }
        LetterVerification again = verify(m, policy, second, true);
        String both = verdict + ">" + again.outcome().name() + "(" + again.rules() + ")";
        if (again.outcome() == LetterVerification.Outcome.PASS) {
            return new Closed(second, both, 1);
        }
        if (again.closesQuiet()) {
            return new Closed(quiet(m, LetterPolicy.QUIET), both, 1);
        }
        return new Closed(fixedFallback(m, policy, second), both, 1);
    }

    private LetterVerification verify(LetterMaterials m, LetterPolicy policy, Draft draft, boolean background) {
        GeminiLetterVerifier.Draft target = verifierDraft(draft);
        return background ? verifier.verify(m, policy, target) : verifier.verifyOnOpen(m, policy, target);
    }

    /** 검증기에 넘기는 글 — 작은 일은 모델이 다시 쓴 맞춤 제목만(정본 제목은 연결표에서 검토를 마쳤다). */
    private GeminiLetterVerifier.Draft verifierDraft(Draft d) {
        List<String> tailored = d.actions().stream().filter(a -> !a.title().equals(canonicalTitle(a.actionId())))
                .map(ActionDraft::title).toList();
        return new GeminiLetterVerifier.Draft(d.quote(), d.insight(), d.tonightQuestion(), tailored);
    }

    private Draft canonicalTitles(Draft d) {
        return d.withActions(d.actions().stream()
                .map(a -> new ActionDraft(a.actionId(), canonicalTitle(a.actionId()), a.source(), a.chosen()))
                .toList());
    }

    private String canonicalTitle(String actionId) {
        return catalog.snapshot().actions().stream().filter(a -> a.id().equals(actionId))
                .map(NeedActionCatalog.Action::title).findFirst().orElse(actionId);
    }

    /** 고정 기본 편지(V5) — 인용 없음 · 기본 본문 · 기본 첫 질문 · 작은 일은 정책 규칙대로 정본 제목만. */
    private Draft fixedFallback(LetterMaterials m, LetterPolicy policy, Draft draft) {
        Draft fixed = new Draft(LetterStatus.FALLBACK, null, LetterRules.defaultBody(m.letterDate()),
                LetterRules.DEFAULT_TONIGHT_QUESTION, draft.needs(), draft.needsSource(), draft.actions(), policy);
        return canonicalTitles(fixed);
    }

    private static Draft quiet(LetterMaterials m, LetterPolicy policy) {
        return new Draft(LetterStatus.QUIET, null, LetterRules.quietBody(), null, List.of(), null, List.of(), policy);
    }

    private static boolean gentleAction(NeedActionCatalog.Action action) {
        return LetterRules.GENTLE_ACTION_IDS.contains(action.id());
    }

    private static void pause() {
        try {
            Thread.sleep(Duration.ofSeconds(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 정책을 먼저 남긴다 — 내 시도일 때만. */
    private void recordPolicyIfOwner(Long letterId, Instant claimedAt, LetterPolicy policy) {
        transaction.executeWithoutResult(status -> ownedForUpdate(letterId, claimedAt)
                .ifPresent(l -> l.recordPolicy(policy)));
    }

    /** 기록 — 등급 · 결정표 줄 · 정책 · 검증 결과 · 지연만. 일기 · 편지 · 근거 문구는 남기지 않는다. */
    private void logGate(String mode, LetterMaterials m, LetterSafetyAssessor.Assessment a, String result,
            String verdict, int regenerations) {
        String judge = a.judgment().map(j -> j.todayCurrent().key() + "/" + j.todayMentioned().key() + "/"
                + j.historyRisk().key() + "/" + j.trajectory().name().toLowerCase(java.util.Locale.ROOT)
                + (a.usable() ? "" : "/unusable")).orElse("none");
        log.info("letter gate mode={} letterId={} row={} policy={} ruleToday={} ruleHistory={} pastDiaries={} "
                        + "judge={} judgeMs={} verdict={} regenerations={} result={}",
                mode, m.letterId(), a.decision().row(), a.policy(), a.ruleToday().key(), a.ruleHistory().key(),
                a.pastDiaries(), judge, a.judgeMillis(), verdict, regenerations, result);
        meters.counter("letter.gate", "mode", mode, "row", a.decision().row(), "result", result).increment();
    }

    // ── 열 때 ────────────────────────────────────────────────────────────

    /**
     * 열 때 보충 — 그대로 보여 줄 수 있으면 아무것도 안 하고 empty. 그 자리에서 만들었으면 저장한
     * 뒤 empty. 다른 쪽이 만드는 중이라 기다려도 끝나지 않았으면, 저장하지 않은 기본 편지(위기면
     * 조용한 편지)를 돌려준다 — 다른 쪽이 곧 진짜 편지를 저장한다.
     */
    Optional<Draft> ensureShowable(Letter letter) {
        if (letter.isShowable()) {
            quietIfRiskAfterDiary(letter);
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
                return materialsReader.read(letter).map(m -> unsavedDraft(letter, m));
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
            draft = gate.mode() == LetterGateProperties.Mode.ON ? guardedOpenDraft(letter, m)
                    : m.crisis() ? quiet(m) : openDraft(m);
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
     * 열 때 다시 확인 — 아직 열지 않은 편지인데 일기 날 이후 위기 안내 기록이 생겼으면, 행을 잠근 채 조용한 편지로
     * 바꾸고 인용 · 작은 일을 지운다. 이미 연 편지는 사용자가 읽었으므로 바꾸지 않는다(화면이 갑자기 달라진다).
     */
    private void quietIfRiskAfterDiary(Letter letter) {
        if (letter.getOpenedAt() != null || letter.getStatus() == LetterStatus.QUIET
                || !materialsReader.riskSinceDiaryDay(letter)) {
            return;
        }
        transaction.executeWithoutResult(status -> letterRepository.findForUpdate(letter.getId())
                .filter(l -> l.getOpenedAt() == null && l.getStatus().isDone() && l.getStatus() != LetterStatus.QUIET)
                .ifPresent(l -> {
                    l.complete(LetterStatus.QUIET, null, LetterRules.quietBody(), null, null, null, PROMPT_VERSION,
                            Instant.now());
                    letterActionRepository.deleteByLetterId(l.getId());
                    log.info("letter quieted on open letterId={} reason=risk_after_diary", l.getId());
                    meters.counter("letter.quieted_on_open").increment();
                }));
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

    private record OpenPick(List<Need> needs, NeedsSource source, Optional<GeminiLetterWriter.ActionPick> ids) {
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
        return Optional.of(new Draft(LetterStatus.READY, quote, insight, question, List.of(), null, List.of(), null));
    }

    /**
     * 작은 일 세 칸 — AI 가 고른 연결표 행동 → 연결표 순서대로 채우기. AI 가 첫 번째로 고른 행동이 살아남으면
     * 1번 칸 제목만 맞춤 제목으로 바꾼다(번호 · 출처는 그대로라 정본과 다른 제목이면 맞춤이다).
     * {@code aiAsked} 가 거짓이면(욕구를 모를 때 · 기본 편지) AI 고르기 없이 대체 규칙만 쓴다.
     * {@code aiPick} 이 비어 있으면 AI 고르기가 실패한 것이다(「알맞은 후보 없음」은 빈 목록).
     */
    private Draft withActions(Draft body, LetterMaterials m, List<Need> needs, NeedsSource source,
            boolean aiAsked, Optional<GeminiLetterWriter.ActionPick> aiPick) {
        List<ActionDraft> slots = new ArrayList<>();
        boolean gentle = body.policy() == LetterPolicy.GENTLE;
        String fallbackReason = "no_ai"; // 욕구를 모를 때 · 기다리다 보여 주는 기본 편지는 처음부터 대체 규칙이다.
        if (aiAsked) {
            Optional<List<String>> ids = aiPick.map(GeminiLetterWriter.ActionPick::actionIds);
            List<NeedActionCatalog.Action> picked = ids.map(i -> catalog.selectedActions(needs, i))
                    .orElse(List.of()).stream().filter(a -> !gentle || gentleAction(a)).toList();
            if (gentle && ids.isPresent() && ids.get().isEmpty()) {
                // 조심스러운 편지에서 AI 가 「알맞은 후보 없음」이라고 했으면 채우지 않는다(설계서 5.8절).
                return new Draft(body.status(), body.quote(), body.insight(), body.tonightQuestion(), needs,
                        source, List.of(), body.policy());
            }
            fallbackReason = ids.isEmpty() ? "ai_failed"
                    : !ids.get().isEmpty() && picked.isEmpty() ? "rejected" : "short";
            if ("rejected".equals(fallbackReason)) {
                log.info("letter actions rejected letterId={} reason=out_of_catalog", m.letterId());
            }
            for (NeedActionCatalog.Action action : picked) {
                addIfRoom(slots, action, LetterActionSource.CATALOG);
            }
            if (!picked.isEmpty() && !gentle) { // 조심스러운 편지는 맞춤 제목 없이 정본 제목만
                tailorFirst(slots, aiPick.get());
            }
        }
        int beforeFallback = slots.size();
        for (NeedActionCatalog.Action action : catalog.candidatesFor(needs)) {
            if (!gentle || gentleAction(action)) {
                addIfRoom(slots, action, LetterActionSource.FALLBACK);
            }
        }
        if (gentle && slots.isEmpty()) { // 그 욕구에 낮은 부담 행동이 없으면 「휴식」의 것으로
            for (NeedActionCatalog.Action action : catalog.candidatesFor(List.of(DEFAULT_NEED))) {
                if (gentleAction(action)) {
                    addIfRoom(slots, action, LetterActionSource.FALLBACK);
                }
            }
        }
        if (slots.size() > beforeFallback) {
            meters.counter("letter.actions.fallback", "reason", fallbackReason).increment();
        }
        return new Draft(body.status(), body.quote(), body.insight(), body.tonightQuestion(), needs,
                source, slots, body.policy());
    }

    /**
     * 맞춤 제목은 모델이 첫 번째로 보낸 번호의 행동을 위해 쓴 문장이다. 그 번호가 탈락해 다른 행동이 1번 칸에
     * 올라왔으면 엉뚱한 행동에 붙게 되므로 버린다. 맞춤을 허용하지 않는 행동이거나 틀을 어겨도 버리고 정본 제목을 둔다.
     */
    private void tailorFirst(List<ActionDraft> slots, GeminiLetterWriter.ActionPick pick) {
        ActionDraft first = slots.get(0);
        String firstId = pick.actionIds().isEmpty() || pick.actionIds().get(0) == null ? null
                : pick.actionIds().get(0).strip();
        String title = LetterRules.unwrapTailoredTitle(pick.tailoredTitle());
        String result;
        if (!first.actionId().equals(firstId)) {
            result = "first_rejected";
        } else if (!LetterRules.TAILORABLE_ACTION_IDS.contains(first.actionId())) {
            result = "not_tailorable";
        } else if (title == null || title.isEmpty()) {
            result = "missing";
        } else if (!LetterRules.isTailoredTitle(title)) {
            result = "invalid";
        } else {
            slots.set(0, new ActionDraft(first.actionId(), title, first.source(),
                    first.chosen()));
            result = "applied";
        }
        meters.counter("letter.actions.tailored", "result", result).increment();
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

    /**
     * 기본 편지 본문. 인용은 일기 첫 문장을 거르지 않고 자르므로, 규칙 검사에 아무것도 걸리지 않을 때만 둔다
     * (자책 · 위기 표현을 안전하게 닫으려는 편지가 그대로 인용하지 않게).
     */
    private Draft fallbackBody(LetterMaterials m) {
        String quote = LetterRules.fallbackQuote(m.diary());
        if (quote != null && safetyPolicy.scan(quote).level() != SafetyLevel.NONE) {
            log.info("letter quote dropped letterId={} reason=fallback_unsafe", m.letterId());
            meters.counter("letter.fallback.quote.dropped").increment();
            quote = null;
        }
        return new Draft(LetterStatus.FALLBACK, quote,
                LetterRules.defaultBody(m.letterDate()), LetterRules.DEFAULT_TONIGHT_QUESTION,
                List.of(), null, List.of(), null);
    }

    /** 조용한 편지 — 인용 · 작은 일 · 첫 질문 예고가 없다. */
    private static Draft quiet(LetterMaterials m) {
        return new Draft(LetterStatus.QUIET, null, LetterRules.quietBody(), null,
                List.of(), null, List.of(), null);
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
            if (draft.policy() != null) {
                letter.recordPolicy(draft.policy());
            }
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
