package com.momentory.wish.application;

import com.momentory.common.time.DayBoundary;
import com.momentory.retrospect.domain.safety.PromptGuard;
import com.momentory.retrospect.domain.safety.SafetyLevel;
import com.momentory.retrospect.domain.safety.SafetyPolicy;
import com.momentory.user.application.AuthenticatedUserNotFoundException;
import com.momentory.user.infrastructure.UserRepository;
import com.momentory.wish.application.WishResults.Author;
import com.momentory.wish.application.WishResults.EndReason;
import com.momentory.wish.application.WishResults.FloatingWish;
import com.momentory.wish.application.WishResults.MyWishes;
import com.momentory.wish.application.WishResults.PastWish;
import com.momentory.wish.application.WishResults.Pond;
import com.momentory.wish.application.WishResults.PondWish;
import com.momentory.wish.domain.BabaWishes;
import com.momentory.wish.domain.Wish;
import com.momentory.wish.domain.WishOutcome;
import com.momentory.wish.domain.WishReport;
import com.momentory.wish.domain.WishRuleAgreement;
import com.momentory.wish.infrastructure.WishJoinRepository;
import com.momentory.wish.infrastructure.WishReportRepository;
import com.momentory.wish.infrastructure.WishRepository;
import com.momentory.wish.infrastructure.WishRuleAgreementRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class WishService {

    static final int BATCH_SIZE = 10;
    /** 남의 소원이 이보다 적으면 바바의 소원으로 채운다. */
    static final int POND_MINIMUM = 5;

    static final String REJECT_PROFANITY = "모두가 보는 연못에 띄우기 어려운 말이 있어요";
    static final String REJECT_HARM = "누군가 잘못되길 바라는 말은 띄울 수 없어요";
    static final String REJECT_NOT_ALLOWED = "광고나 모두가 보기 어려운 말은 띄울 수 없어요";
    static final String REJECT_NOT_A_WISH = "바라는 것을 한 문장으로 적어 주세요";

    /** AI 가 놓친 연락처는 규칙으로 한 번 더 지운다. */
    private static final Pattern CONTACT = Pattern.compile(
            "(?:https?://|www\\.)\\S+|[\\w.+-]+@[\\w-]+\\.[\\w.]+|\\d{2,3}[- .]?\\d{3,4}[- .]?\\d{4}");

    private final WishRepository wishRepository;
    private final WishJoinRepository joinRepository;
    private final WishReportRepository reportRepository;
    private final WishRuleAgreementRepository agreementRepository;
    private final UserRepository userRepository;
    private final SafetyPolicy safetyPolicy;
    private final WishReviewAi reviewAi;
    private final WishReviewToken reviewToken;

    public WishService(WishRepository wishRepository, WishJoinRepository joinRepository,
            WishReportRepository reportRepository, WishRuleAgreementRepository agreementRepository,
            UserRepository userRepository, SafetyPolicy safetyPolicy, WishReviewAi reviewAi,
            WishReviewToken reviewToken) {
        this.wishRepository = wishRepository;
        this.joinRepository = joinRepository;
        this.reportRepository = reportRepository;
        this.agreementRepository = agreementRepository;
        this.userRepository = userRepository;
        this.safetyPolicy = safetyPolicy;
        this.reviewAi = reviewAi;
        this.reviewToken = reviewToken;
    }

    @Transactional(readOnly = true)
    public Pond pond(Long userId) {
        requireUser(userId);
        Instant now = Instant.now();
        LocalDate today = DayBoundary.today();
        List<PondWish> wishes = new ArrayList<>(wishRepository.pickForPond(userId, now, today.toString(), BATCH_SIZE)
                .stream()
                .map(row -> new PondWish(row.getId(), row.getContent(), row.getDaysLeft(), row.getLight(),
                        row.getJoined(), Author.USER))
                .toList());
        int shortBy = POND_MINIMUM - wishes.size();
        if (shortBy > 0) {
            BabaWishes.forDay(today, shortBy).forEach(baba -> wishes.add(new PondWish(baba.id(), baba.content(),
                    (int) Wish.FLOATING_PERIOD.toDays(), true, false, Author.BABA)));
        }
        long floatingCount = Math.max(wishRepository.countFloatingAuthors(userId, now), wishes.size());
        return new Pond(hasAgreed(userId), wishRepository.existsByUserIdAndWishDate(userId, today),
                wishRepository.hasFloating(userId, now), floatingCount, wishes);
    }

    @Transactional
    public void agreeToRules(Long userId) {
        requireUser(userId);
        agreementRepository.save(new WishRuleAgreement(userId, WishRuleAgreement.CURRENT_VERSION, Instant.now()));
    }

    /**
     * 띄우기 전 심사 — 낱말 규칙을 먼저 보고 AI 에게 묻는다. 위기가 항상 먼저다. 아무것도 저장하지 않는다.
     * AI 를 기다리는 동안 DB 연결을 잡지 않으려고 트랜잭션을 열지 않는다.
     */
    // ponytail: 심사 호출 횟수는 막지 않는다. Gemini 비용이 눈에 띄게 늘면 사람마다 하루 횟수를 둔다
    public WishReview review(Long userId, String content) {
        requireWritable(userId);
        SafetyPolicy.ScanResult scan = safetyPolicy.scan(content);
        if (scan.level() == SafetyLevel.IMMINENT || scan.level() == SafetyLevel.RISK) {
            return WishReview.crisis();
        }
        if (scan.flags().contains(SafetyPolicy.FLAG_PROFANITY)) {
            return WishReview.rejected(REJECT_PROFANITY);
        }
        if (PromptGuard.inspect(content).isPresent()) {
            return WishReview.rejected(REJECT_NOT_A_WISH);
        }
        WishReviewAi.Verdict verdict = reviewAi.review(content).orElseThrow(WishException::reviewUnavailable);
        return switch (verdict.decision()) {
            case CRISIS -> WishReview.crisis();
            case HARM -> WishReview.rejected(REJECT_HARM);
            case NOT_ALLOWED -> WishReview.rejected(REJECT_NOT_ALLOWED);
            case NOT_A_WISH -> WishReview.rejected(REJECT_NOT_A_WISH);
            case PASS -> passed(userId, content, verdict);
        };
    }

    private WishReview passed(Long userId, String original, WishReviewAi.Verdict verdict) {
        String cleaned = verdict.content() == null ? ""
                : CONTACT.matcher(verdict.content()).replaceAll("").replaceAll("\\s+", " ").strip();
        // 이름 · 연락처만 고치는 심사다. 글이 비거나 원문보다 크게 달라졌으면 믿지 않는다
        if (cleaned.isEmpty() || cleaned.length() > Wish.CONTENT_MAX_LENGTH
                || cleaned.length() < original.length() / 3) {
            throw WishException.reviewUnavailable();
        }
        WishReview.Result result = cleaned.equals(original) ? WishReview.Result.PASS : WishReview.Result.EDITED;
        return new WishReview(result, cleaned,
                reviewToken.issue(userId, cleaned, verdict.light(), Instant.now()), null);
    }

    @Transactional
    public FloatingWish post(Long userId, String content, String token) {
        requireWritable(userId);
        Instant now = Instant.now();
        boolean light = reviewToken.verify(userId, content, token, now)
                .orElseThrow(WishException::reviewInvalid);
        try {
            Wish wish = wishRepository.saveAndFlush(Wish.create(userId, DayBoundary.today(), content, light, now));
            return new FloatingWish(wish.getId(), wish.getContent(), daysLeft(wish, now), false);
        } catch (DataIntegrityViolationException e) {
            throw WishException.dailyLimit();
        }
    }

    @Transactional
    public void join(Long userId, Long wishId) {
        Wish wish = floatingOfOther(userId, wishId);
        joinRepository.joinOnce(wish.getId(), userId);
    }

    @Transactional
    public void report(Long userId, Long wishId) {
        Wish wish = floatingOfOther(userId, wishId);
        reportRepository.reportOnce(wish.getId(), userId, wish.getUserId());
        if (reportRepository.countByWishId(wish.getId()) >= WishReport.TAKE_DOWN_REPORTERS) {
            wish.takeDown(Instant.now());
        }
    }

    /** 신고 되돌리기 — 잘못 눌렀을 때. 이미 내려간 소원을 다시 올리지는 않는다. */
    @Transactional
    public void undoReport(Long userId, Long wishId) {
        requireUser(userId);
        reportRepository.undo(wishId, userId);
    }

    /** 지운 소원도 그날의 몫을 돌려주지 않는다. */
    @Transactional
    public void delete(Long userId, Long wishId) {
        mine(userId, wishId).delete(Instant.now());
    }

    @Transactional
    public void answer(Long userId, Long wishId, WishOutcome outcome) {
        mine(userId, wishId).answer(outcome);
    }

    // ponytail: 페이지 없이 전부 돌려준다. 하루 하나라 천천히 쌓인다. 느려지면 커서 페이지로 바꾼다
    @Transactional(readOnly = true)
    public MyWishes myWishes(Long userId) {
        requireUser(userId);
        Instant now = Instant.now();
        List<Wish> wishes = wishRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(userId);
        Set<Long> joined = wishes.isEmpty() ? Set.of()
                : Set.copyOf(joinRepository.findJoinedWishIds(wishes.stream().map(Wish::getId).toList()));
        List<FloatingWish> floating = wishes.stream()
                .filter(wish -> wish.isFloatingAt(now))
                .map(wish -> new FloatingWish(wish.getId(), wish.getContent(), daysLeft(wish, now),
                        joined.contains(wish.getId())))
                .toList();
        List<PastWish> past = wishes.stream()
                .filter(wish -> !wish.isFloatingAt(now))
                .map(wish -> new PastWish(wish.getId(), wish.getContent(),
                        wish.getTakenDownAt() == null ? EndReason.EXPIRED : EndReason.REPORTED, wish.getOutcome()))
                .toList();
        return new MyWishes(wishRepository.existsByUserIdAndWishDate(userId, DayBoundary.today()), floating, past);
    }

    private static int daysLeft(Wish wish, Instant now) {
        long seconds = Duration.between(now, wish.getExpiresAt()).toSeconds();
        return (int) Math.max(1, Math.ceilDiv(seconds, Duration.ofDays(1).toSeconds()));
    }

    private void requireWritable(Long userId) {
        requireUser(userId);
        if (!hasAgreed(userId)) {
            throw WishException.rulesRequired();
        }
        if (wishRepository.existsByUserIdAndWishDate(userId, DayBoundary.today())) {
            throw WishException.dailyLimit();
        }
    }

    private boolean hasAgreed(Long userId) {
        return agreementRepository.findById(userId)
                .filter(agreement -> agreement.getVersion() >= WishRuleAgreement.CURRENT_VERSION)
                .isPresent();
    }

    private Wish floatingOfOther(Long userId, Long wishId) {
        requireUser(userId);
        return wishRepository.findById(wishId)
                .filter(wish -> wish.isFloatingAt(Instant.now()) && !wish.getUserId().equals(userId))
                .orElseThrow(WishException::notFound);
    }

    private Wish mine(Long userId, Long wishId) {
        requireUser(userId);
        return wishRepository.findById(wishId)
                .filter(wish -> wish.getUserId().equals(userId) && !wish.getContent().isEmpty())
                .orElseThrow(WishException::notFound);
    }

    private void requireUser(Long userId) {
        userRepository.findById(userId).orElseThrow(AuthenticatedUserNotFoundException::new);
    }
}
