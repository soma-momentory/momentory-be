package com.momentory.letter.application;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.momentory.actioncard.domain.ActionCard;
import com.momentory.common.time.DayBoundary;
import com.momentory.actioncard.infrastructure.persistence.ActionCardRepository;
import com.momentory.diary.domain.Diary;
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.letter.domain.Letter;
import com.momentory.letter.domain.LetterMaterials;
import com.momentory.letter.infrastructure.LetterActionRepository;
import com.momentory.letter.infrastructure.LetterRepository;
import com.momentory.retrospect.domain.Message;
import com.momentory.retrospect.domain.Needs;
import com.momentory.retrospect.domain.safety.SafetyPolicy;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.retrospect.infrastructure.persistence.RetrospectStateCodec;
import com.momentory.retrospect.infrastructure.persistence.SafetyEventRepository;
import com.momentory.schedule.domain.Schedule;
import com.momentory.schedule.infrastructure.ScheduleRepository;
import com.momentory.user.domain.UserProfile;
import com.momentory.user.infrastructure.UserProfileRepository;

/** 편지 재료를 여러 저장소에서 모은다(PRD 5-1). 읽기만 하고, 본문을 로그에 남기지 않는다. */
@Component
class LetterMaterialsReader {

    private final LetterRepository letterRepository;
    private final LetterActionRepository letterActionRepository;
    private final DiaryRepository diaryRepository;
    private final RetrospectRepository retrospectRepository;
    private final RetrospectStateCodec codec;
    private final ActionCardRepository actionCardRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserProfileRepository userProfileRepository;
    private final SafetyPolicy safetyPolicy;
    private final LetterWishCards wishCards;
    private final com.momentory.diary.infrastructure.DiaryEventCodec diaryEventCodec;
    private final SafetyEventRepository safetyEvents;
    /** 꺼져 있으면 전날 고른 일을 편지 재료에 넣지 않는다 — 고를 수 없게 된 일을 편지가 다시 꺼내지 않게(2026-11-17). */
    private final boolean actionsEnabled;

    LetterMaterialsReader(LetterRepository letterRepository,
            LetterActionRepository letterActionRepository, DiaryRepository diaryRepository,
            RetrospectRepository retrospectRepository, RetrospectStateCodec codec,
            ActionCardRepository actionCardRepository, ScheduleRepository scheduleRepository,
            UserProfileRepository userProfileRepository, SafetyPolicy safetyPolicy,
            LetterWishCards wishCards, com.momentory.diary.infrastructure.DiaryEventCodec diaryEventCodec,
            SafetyEventRepository safetyEvents,
            @Value("${momentory.letter.actions-enabled:true}") boolean actionsEnabled) {
        this.letterRepository = letterRepository;
        this.letterActionRepository = letterActionRepository;
        this.diaryRepository = diaryRepository;
        this.retrospectRepository = retrospectRepository;
        this.codec = codec;
        this.actionCardRepository = actionCardRepository;
        this.scheduleRepository = scheduleRepository;
        this.userProfileRepository = userProfileRepository;
        this.safetyPolicy = safetyPolicy;
        this.wishCards = wishCards;
        this.diaryEventCodec = diaryEventCodec;
        this.safetyEvents = safetyEvents;
        this.actionsEnabled = actionsEnabled;
    }

    /** 만드는 사이 일기가 지워졌으면 empty. */
    Optional<LetterMaterials> read(Letter letter) {
        Optional<Diary> found = diaryRepository.findById(letter.getDiaryId());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Diary diary = found.get();
        var state = Optional.ofNullable(diary.getRetrospectId())
                .flatMap(retrospectRepository::findById)
                .map(r -> codec.deserialize(r.getStateJson()));
        List<Message> messages = state.map(s -> s.messages()).orElse(List.of());
        List<String> events = diary.getRetrospectId() == null
                ? diaryEventCodec.deserialize(diary.getEventsJson()).stream()
                        .map(event -> event.label().equals(event.summary()) ? event.summary()
                                : event.label() + ": " + event.summary()).toList()
                : state.map(s -> s.events().stream().map(event -> event.summary())
                        .filter(java.util.Objects::nonNull).toList()).orElse(List.of());
        Optional<ActionCard> card = Optional.ofNullable(diary.getRetrospectId())
                .flatMap(actionCardRepository::findByRetrospectId);

        return Optional.of(new LetterMaterials(
                letter.getId(),
                letter.getLetterDate(),
                userProfileRepository.findById(letter.getUserId()).map(UserProfile::getNickname)
                        .orElse(null),
                diary.getOriginal(),
                messages.stream().filter(Message::isUser).map(Message::content)
                        .filter(c -> c != null && !c.isBlank()).toList(),
                // 「이어서 얘기하기」가 누적 안전 상태를 지우므로, 남은 안내 문구로 판정한다. 위기 안내 뒤 나갔다가
                // 같은 날 새로 회고하면 새 대화에는 안내가 없으므로, 일기 날 이후의 안전 기록도 함께 본다.
                messages.stream().filter(Message::isAssistant).map(Message::content)
                        .anyMatch(safetyPolicy::isGuidance)
                        || riskSinceDiaryDay(letter),
                card.map(ActionCard::getNeeds).map(LetterMaterialsReader::csv).orElse(List.of())
                        .stream().map(Needs::byWord).flatMap(Optional::stream).toList(),
                schedules(letter, 0).stream()
                        .map(s -> s.getTitle() + (s.isCompleted() ? " (했음)" : " (안 했음)")).toList(),
                schedules(letter, 1).stream().map(Schedule::getTitle).toList(),
                previousAction(letter), events));
    }

    /** 이 편지의 일기 날(04:00 경계) 시작 이후 위기 안내 기록이 있는가 — 열 때 다시 확인할 때도 쓴다. */
    boolean riskSinceDiaryDay(Letter letter) {
        return safetyEvents.existsByUserIdAndOccurredAtGreaterThanEqual(letter.getUserId(),
                DayBoundary.startOfDay(letter.getLetterDate()));
    }

    /**
     * 일기 날 전 {@code days}일 안에, 위기 안내 기록은 있는데 그날(04:00 경계) 일기가 없는 날이 있는가. 위기 안내로 멈춘
     * 회고는 일기를 남기지 않아 지난 일기로는 보이지 않는다. 그날 일기가 남았으면 지난 일기로 이미 읽으므로 세지 않는다.
     * 일기 날 이후의 기록은 {@link #riskSinceDiaryDay} 가 본다.
     */
    boolean crisisWithoutDiaryBefore(Letter letter, int days) {
        Long userId = letter.getUserId();
        return safetyEvents.findByUserIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(userId,
                        DayBoundary.startOfDay(letter.getLetterDate().minusDays(days)),
                        DayBoundary.startOfDay(letter.getLetterDate())).stream()
                .map(e -> DayBoundary.toLocalDate(e.getOccurredAt()))
                .distinct()
                .anyMatch(day -> !diaryRepository.existsByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(userId,
                        DayBoundary.startOfDay(day), DayBoundary.startOfDay(day.plusDays(1))));
    }

    private List<Schedule> schedules(Letter letter, int daysAfter) {
        return scheduleRepository.findByUserIdAndScheduleDateAndDeletedAtIsNullOrderByDisplayOrderAscIdAsc(
                letter.getUserId(), letter.getLetterDate().plusDays(daysAfter)).stream()
                .filter(s -> !s.isHidden()).toList();
    }

    /**
     * 전날 편지에서 고른 일 — 편지를 받는 날 기준으로 「어제 해 보기로 한 일」이다(편지 PRD 14-4).
     * 해 봤는지는 바람카드의 완료 표시가 원본이다. 쉼터에서 나중에 체크한 것도 그대로 반영된다.
     */
    private String previousAction(Letter letter) {
        if (!actionsEnabled) {
            return null;
        }
        return letterRepository
                .findFirstByUserIdAndLetterDateLessThanOrderByLetterDateDesc(letter.getUserId(),
                        letter.getLetterDate())
                .filter(previous -> previous.getLetterDate().equals(letter.getLetterDate().minusDays(1)))
                .flatMap(previous -> letterActionRepository.findByLetterIdAndChosenTrue(previous.getId())
                        .flatMap(action -> {
                            if (wishCards.tried(previous, action)) {
                                return Optional.of(action.getTitle() + " (해 봤다고 함)");
                            }
                            return "NOT_DONE".equals(action.getResult())
                                    ? Optional.of(action.getTitle() + " (못 했다고 함 · 못 해도 괜찮다는 쪽으로)")
                                    : Optional.empty();
                        }))
                .orElse(null);
    }

    private static List<String> csv(String value) {
        return value == null ? List.of()
                : Arrays.stream(value.split(",")).map(String::strip).filter(Objects::nonNull)
                        .filter(s -> !s.isEmpty()).toList();
    }
}
