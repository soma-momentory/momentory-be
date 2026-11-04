package com.momentory.report.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.momentory.common.time.DayBoundary;
import com.momentory.report.domain.DayStory;
import com.momentory.report.domain.WeekTone;
import com.momentory.report.domain.WeeklyPages;
import com.momentory.report.domain.WeeklyReportRules;
import com.momentory.report.domain.WeeklyReportWriting;
import com.momentory.report.infrastructure.WeeklyReportWritingRepository;
import com.momentory.report.infrastructure.ai.GeminiWeeklyReportWriter;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 한 주의 AI 글을 돌려준다 — 있으면 그대로, 없으면 그 자리에서 만들어 저장한다.
 *
 * <p>Gemini 호출은 트랜잭션 밖에서 한다. 실패하면 저장하지 않고 대체 문장을 돌려주므로 다음에 열 때 다시
 * 만든다. 위기 안내가 나온 주는 AI 를 부르지 않는다. 끝나지 않은 주는 글이 바뀔 수 있어 만들지 않는다.
 */
@Component
class WeeklyWritingService {

    private static final Logger log = LoggerFactory.getLogger(WeeklyWritingService.class);
    static final String PROMPT_VERSION = "v1";

    private final WeeklyReportWritingRepository repository;
    private final GeminiWeeklyReportWriter writer;
    private final TransactionTemplate transaction;
    private final MeterRegistry meters;

    WeeklyWritingService(WeeklyReportWritingRepository repository, GeminiWeeklyReportWriter writer,
            TransactionTemplate transaction, MeterRegistry meters) {
        this.repository = repository;
        this.writer = writer;
        this.transaction = transaction;
        this.meters = meters;
    }

    WeeklyWriting ensure(WeeklyMaterials m) {
        long diaryCount = m.stories().size();
        if (WeeklyPages.tone(m.stories()) == WeekTone.CRISIS) {
            return new WeeklyWriting(WeeklyReportRules.QUIET_COVER_LINE, WeeklyReportRules.QUIET_REVIEW, null, null);
        }
        WeeklyWriting fallback = new WeeklyWriting(WeeklyReportRules.fallbackCoverLine(diaryCount), null, null, null);
        if (diaryCount == 0 || !m.weekEnd().isBefore(DayBoundary.today())) {
            return fallback;
        }
        String digest = digest(m);
        Optional<WeeklyReportWriting> saved = repository.findByUserIdAndWeekStart(m.userId(), m.weekStart());
        if (saved.filter(w -> digest.equals(w.getSourceDigest())).isPresent()) {
            return toWriting(saved.get());
        }
        Optional<WeeklyWriting> written = writer.write(m.userId(), m.weekStart().toString(), m.nickname(), m.stories())
                .flatMap(WeeklyWritingService::checked);
        meters.counter("report.writing", "status", written.isPresent() ? "written" : "failed").increment();
        if (written.isEmpty()) {
            return fallback;
        }
        save(m, written.get(), digest);
        return written.get();
    }

    /** 표지 문장은 꼭 있어야 한다. 나머지는 어긋나면 그 칸만 비운다 — 화면이 그 칸을 빼고 보여 준다. */
    private static Optional<WeeklyWriting> checked(GeminiWeeklyReportWriter.Draft draft) {
        String cover = WeeklyReportRules.checked(draft.coverLine(), WeeklyReportRules.COVER_LINE_MAX_LENGTH);
        if (cover == null) {
            return Optional.empty();
        }
        return Optional.of(new WeeklyWriting(cover,
                WeeklyReportRules.checked(draft.review(), WeeklyReportRules.REVIEW_MAX_LENGTH),
                WeeklyReportRules.checked(draft.goodWords(), WeeklyReportRules.GOOD_WORDS_MAX_LENGTH),
                WeeklyReportRules.checkedOkLine(draft.okLine())));
    }

    private void save(WeeklyMaterials m, WeeklyWriting w, String digest) {
        try {
            transaction.executeWithoutResult(status -> {
                WeeklyReportWriting row = repository.findByUserIdAndWeekStart(m.userId(), m.weekStart())
                        .orElseGet(() -> WeeklyReportWriting.create(m.userId(), m.weekStart()));
                row.write(w.coverLine(), w.review(), w.goodWords(), w.okLine(), digest, PROMPT_VERSION);
                repository.save(row);
            });
        } catch (DataIntegrityViolationException e) {
            // 같은 주를 두 곳에서 동시에 처음 열었다 — 먼저 저장한 글이 남고, 이 응답은 방금 쓴 글을 그대로 보여 준다.
            log.info("weekly writing save skipped reason=concurrent weekStart={}", m.weekStart());
        }
    }

    private static WeeklyWriting toWriting(WeeklyReportWriting w) {
        return new WeeklyWriting(w.getCoverLine(), w.getReview(), w.getGoodWords(), w.getOkLine());
    }

    /** 글을 쓸 때 본 일기의 지문 — 일기를 고치거나 지우면 달라져서 다시 쓴다. 프롬프트를 바꿔도 마찬가지다. */
    static String digest(WeeklyMaterials m) {
        StringBuilder source = new StringBuilder(PROMPT_VERSION);
        for (DayStory story : m.stories()) {
            source.append('|').append(story.diaryId()).append(':').append(story.diary());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
