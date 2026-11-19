package com.momentory.letter.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.momentory.actioncard.domain.ActionCard;
import com.momentory.common.time.DayBoundary;
import com.momentory.letter.domain.Letter;
import com.momentory.letter.domain.LetterAction;
import com.momentory.letter.domain.LetterPicture;
import com.momentory.letter.domain.LetterPictureStatus;
import com.momentory.letter.domain.LetterPolicy;
import com.momentory.letter.domain.LetterStatus;
import com.momentory.letter.infrastructure.LetterActionRepository;
import com.momentory.letter.infrastructure.LetterPictureRepository;
import com.momentory.letter.infrastructure.LetterRepository;
import com.momentory.retrospect.domain.safety.SafetyPolicy;
import com.momentory.schedule.infrastructure.ScheduleRepository;

/**
 * 편지 읽기 · 열기 · 작은 일 고르기. 편지는 일기 다음 날(04:00 경계)부터 열린다 — 그 전 날짜는
 * 없는 것처럼 404 다.
 *
 * <p>편지를 보여 주는 길({@link #current} · {@link #byDate})은 트랜잭션을 걸지 않는다. 아직 만들지
 * 못한 편지면 그 자리에서 Gemini 를 불러 보충하기 때문이다(보통 2~3초 · 한도 8초, 다른 요청이 만드는 중이면 기다림 8초가 더해진다)({@link LetterGenerator}).
 */
@Service
public class LetterService {

    private final LetterRepository letterRepository;
    private final LetterActionRepository letterActionRepository;
    private final LetterGenerator generator;
    private final ScheduleRepository scheduleRepository;
    private final SafetyPolicy safetyPolicy;
    private final LetterWishCards wishCards;
    /** 꺼져 있으면 지난 편지의 작은 일도 응답에 싣지 않고, 고르기도 받지 않는다(2026-11-17). 저장된 칸은 지우지 않는다. */
    private final boolean actionsEnabled;
    private final LetterPictureRepository pictureRepository;
    private final LetterPictureProperties pictureProperties;

    public LetterService(LetterRepository letterRepository,
            LetterActionRepository letterActionRepository, LetterGenerator generator,
            ScheduleRepository scheduleRepository, SafetyPolicy safetyPolicy, LetterWishCards wishCards,
            @Value("${momentory.letter.actions-enabled:true}") boolean actionsEnabled,
            LetterPictureRepository pictureRepository, LetterPictureProperties pictureProperties) {
        this.letterRepository = letterRepository;
        this.letterActionRepository = letterActionRepository;
        this.generator = generator;
        this.scheduleRepository = scheduleRepository;
        this.safetyPolicy = safetyPolicy;
        this.wishCards = wishCards;
        this.actionsEnabled = actionsEnabled;
        this.pictureRepository = pictureRepository;
        this.pictureProperties = pictureProperties;
    }

    /** 열 수 있는 가장 최근 편지 — 오래된 것이어도 준다. 홈 아이콘의 점은 {@code isNew} 로 판단한다. */
    public Optional<LetterView> current(Long userId) {
        LocalDate today = DayBoundary.today();
        return letterRepository.findFirstByUserIdAndLetterDateLessThanOrderByLetterDateDesc(userId, today)
                .map(letter -> show(letter, today));
    }

    /** 그 날짜 일기의 편지 — 넘겨 보기 · 일기 상세에서 연다. */
    public LetterView byDate(Long userId, LocalDate date) {
        LocalDate today = DayBoundary.today();
        Letter letter = letterRepository.findByUserIdAndLetterDate(userId, date)
                .filter(l -> l.isOpenableOn(today))
                .orElseThrow(LetterNotFoundException::new);
        return show(letter, today);
    }

    /** 넘겨 보기 목록 — 열 수 있는 편지의 날짜만, 최신순. 여기서는 편지를 만들지 않는다. */
    @Transactional(readOnly = true)
    public List<LetterView.Summary> list(Long userId) {
        LocalDate today = DayBoundary.today();
        return letterRepository.findByUserIdAndLetterDateLessThanOrderByLetterDateDesc(userId, today).stream()
                .map(l -> new LetterView.Summary(l.getId(), l.getLetterDate(), l.isNewOn(today), l.getOpenedAt()))
                .toList();
    }

    /** 처음 연 시각을 남긴다. 여러 번 불러도 처음 값만 남는다. */
    @Transactional
    public void open(Long userId, Long id) {
        openable(userId, id, DayBoundary.today()).markOpened(Instant.now());
    }

    /**
     * 작은 일 고르기 · 바꾸기 · 풀기. {@code position} 이 null 이면 선택을 푼다. 한 편지에 고른 일은
     * 하나뿐이라(부분 유일 인덱스), 다른 칸의 선택을 먼저 풀어 DB 에 반영한 뒤 새 칸을 고른다.
     */
    @Transactional
    public LetterView chooseAction(Long userId, Long id, Integer position, String whenLabel,
            Long scheduleId, Instant remindAt) {
        LocalDate today = DayBoundary.today();
        Letter letter = letterRepository.findByIdAndUserIdForUpdate(id, userId)
                .filter(l -> l.isOpenableOn(today))
                .orElseThrow(LetterNotFoundException::new);
        if (!actionsEnabled || !letter.getStatus().isDone()) {
            throw new LetterActionNotFoundException(); // 작은 일을 껐거나 아직 만들지 못한 편지 — 고를 작은 일이 없다.
        }
        // 작은 일을 골랐다면 편지를 본 것이다. 열람 기록이 빠졌어도 일기를 고칠 때 다시 만들어 고른 일을
        // 지우지 않게 여기서도 남긴다.
        letter.markOpened(Instant.now());
        List<LetterAction> actions = letterActionRepository.findByLetterIdOrderByPositionAsc(id);
        Optional<ActionCard> card = wishCards.of(letter);
        // 해 본 일이면 고르기 · 바꾸기 · 풀기를 모두 막는다(편지 PRD 14). 원본은 카드의 「했어요」라
        // 쉼터에서 취소하면 다시 고를 수 있고, 카드가 없을 때만 밤 회고 답을 본다({@link LetterWishCards#tried}).
        // 카드가 이미 「했어요」면 처음 고르는 것도 막는다 — 해 보지 않은 일이 해 본 일로 남지 않게.
        if (card.map(ActionCard::isDone)
                .orElseGet(() -> actions.stream().anyMatch(a -> a.isChosen() && a.isTried()))) {
            throw new LetterActionLockedException();
        }
        if (position == null) {
            actions.forEach(LetterAction::unchoose);
            card.ifPresent(c -> c.changeSmallAction(null));
            return view(letter, actions, today);
        }
        LetterAction target = actions.stream().filter(a -> a.getPosition() == position)
                .findFirst().orElseThrow(LetterActionNotFoundException::new);
        if (scheduleId != null
                && scheduleRepository.findByIdAndUserIdAndDeletedAtIsNull(scheduleId, userId).isEmpty()) {
            throw new LetterScheduleNotFoundException();
        }
        actions.forEach(LetterAction::unchoose);
        letterActionRepository.flush();
        target.choose(whenLabel, scheduleId, remindAt);
        // 바람카드의 「작은 행동」 줄은 편지에서 고른 일로 채운다(편지 PRD 14-3).
        card.ifPresent(c -> c.changeSmallAction(target.getTitle()));
        return view(letter, actions, today);
    }

    private Letter openable(Long userId, Long id, LocalDate today) {
        return letterRepository.findByIdAndUserId(id, userId)
                .filter(l -> l.isOpenableOn(today))
                .orElseThrow(LetterNotFoundException::new);
    }

    private LetterView show(Letter letter, LocalDate today) {
        return show(letter, today, true);
    }

    private LetterView show(Letter letter, LocalDate today, boolean recheck) {
        Optional<LetterGenerator.Draft> unsaved = generator.ensureShowable(letter);
        if (unsaved.isPresent()) {
            // 다른 쪽이 아직 만드는 중이라 이번에만 보여 주는 편지 — 저장된 칸이 없어 고를 수 없으므로
            // 작은 일은 싣지 않는다. 다음에 열면 저장된 편지가 작은 일과 함께 나간다.
            LetterGenerator.Draft d = unsaved.get();
            return new LetterView(letter.getId(), letter.getLetterDate(), d.status(), d.quote(), d.insight(),
                    d.tonightQuestion(), List.of(), helplines(d.status(), d.policy()), letter.isNewOn(today),
                    letter.getOpenedAt(), false);
        }
        Letter fresh = letterRepository.findById(letter.getId()).orElseThrow(LetterNotFoundException::new);
        if (recheck && !fresh.getStatus().isDone()) {
            return show(fresh, today, false); // 그사이 일기를 고쳐 다시 만들기가 들어왔다 — 한 번 더 본다.
        }
        return view(fresh, letterActionRepository.findByLetterIdOrderByPositionAsc(fresh.getId()), today);
    }

    private LetterView view(Letter letter, List<LetterAction> stored, LocalDate today) {
        List<LetterAction> actions = actionsEnabled ? stored : List.of();
        return new LetterView(letter.getId(), letter.getLetterDate(), letter.getStatus(), letter.getQuote(),
                letter.getInsight(), letter.getTonightQuestion(),
                actions.stream().map(a -> new LetterView.Action(a.getPosition(), a.getTitle(), a.getSource(),
                        a.isChosen(), a.getWhenLabel(), a.getScheduleId(), a.getRemindAt())).toList(),
                helplines(letter.getStatus(), letter.getSafetyPolicy()), letter.isNewOn(today), letter.getOpenedAt(),
                hasPicture(letter));
    }

    /**
     * 그림일기 — 열 수 있는 내 편지의 다 그린 그림만 준다. 아직 열 수 없는 편지(04:00 전)는 편지와 같이 없는 것처럼
     * 다뤄, 편지보다 그림을 먼저 보는 일이 없게 한다.
     */
    @Transactional(readOnly = true)
    public LetterPictureView picture(Long userId, Long id) {
        Letter letter = openable(userId, id, DayBoundary.today());
        if (!pictureProperties.allows(letter.getUserId()) || !letter.allowsPicture()) {
            throw new LetterPictureNotFoundException();
        }
        return pictureRepository.findById(letter.getId())
                .filter(LetterPicture::isReady)
                .map(p -> new LetterPictureView(p.getMimeType(), p.getImage()))
                .orElseThrow(LetterPictureNotFoundException::new);
    }

    private boolean hasPicture(Letter letter) {
        return pictureProperties.allows(letter.getUserId()) && letter.allowsPicture()
                && pictureRepository.existsByLetterIdAndStatus(letter.getId(), LetterPictureStatus.READY);
    }

    /** 조용한 편지는 도움받을 곳 전부, 안전 게이트가 조심스럽게(GENTLE) 쓴 편지는 첫 줄(24시간 상담) 하나. */
    private List<String> helplines(LetterStatus status, LetterPolicy policy) {
        if (status == LetterStatus.QUIET) {
            return safetyPolicy.resources();
        }
        return policy == LetterPolicy.GENTLE ? safetyPolicy.resources().subList(0, 1) : List.of();
    }
}
