package com.momentory.letter.application;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.momentory.actioncard.domain.ActionCard;
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

    LetterMaterialsReader(LetterRepository letterRepository,
            LetterActionRepository letterActionRepository, DiaryRepository diaryRepository,
            RetrospectRepository retrospectRepository, RetrospectStateCodec codec,
            ActionCardRepository actionCardRepository, ScheduleRepository scheduleRepository,
            UserProfileRepository userProfileRepository, SafetyPolicy safetyPolicy,
            LetterWishCards wishCards) {
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
    }

    /** 만드는 사이 일기가 지워졌으면 empty. */
    Optional<LetterMaterials> read(Letter letter) {
        Optional<Diary> found = diaryRepository.findById(letter.getDiaryId());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Diary diary = found.get();
        List<Message> messages = retrospectRepository.findById(diary.getRetrospectId())
                .map(r -> codec.deserialize(r.getStateJson()).messages())
                .orElse(List.of());
        Optional<ActionCard> card = actionCardRepository.findByRetrospectId(diary.getRetrospectId());

        return Optional.of(new LetterMaterials(
                letter.getId(),
                letter.getLetterDate(),
                userProfileRepository.findById(letter.getUserId()).map(UserProfile::getNickname)
                        .orElse(null),
                diary.getOriginal(),
                messages.stream().filter(Message::isUser).map(Message::content)
                        .filter(c -> c != null && !c.isBlank()).toList(),
                // 「이어서 얘기하기」가 누적 안전 상태를 지우므로, 남은 안내 문구로 판정한다.
                messages.stream().filter(Message::isAssistant).map(Message::content)
                        .anyMatch(safetyPolicy::isGuidance),
                card.map(ActionCard::getNeeds).map(LetterMaterialsReader::csv).orElse(List.of())
                        .stream().map(Needs::byWord).flatMap(Optional::stream).toList(),
                schedules(letter, 0).stream()
                        .map(s -> s.getTitle() + (s.isCompleted() ? " (했음)" : " (안 했음)")).toList(),
                schedules(letter, 1).stream().map(Schedule::getTitle).toList(),
                previousAction(letter)));
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
