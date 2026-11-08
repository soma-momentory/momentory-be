package com.momentory.report.application;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.momentory.common.time.DayBoundary;
import com.momentory.diary.domain.Diary;
import com.momentory.diary.domain.DiaryEvent;
import com.momentory.diary.infrastructure.DiaryEventCodec;
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.letter.domain.LetterRules;
import com.momentory.report.domain.DayStory;
import com.momentory.report.domain.WeeklyPages;
import com.momentory.report.domain.WeeklyReportRules;
import com.momentory.restrecord.domain.RestRecord;
import com.momentory.restrecord.infrastructure.RestRecordRepository;
import com.momentory.retrospect.domain.Emotion;
import com.momentory.retrospect.domain.ExtractedEmotion;
import com.momentory.retrospect.domain.ExtractedEvent;
import com.momentory.retrospect.domain.Message;
import com.momentory.retrospect.domain.RetrospectStateSnapshot;
import com.momentory.retrospect.domain.safety.SafetyPolicy;
import com.momentory.retrospect.infrastructure.persistence.RetrospectRepository;
import com.momentory.retrospect.infrastructure.persistence.RetrospectStateCodec;
import com.momentory.schedule.application.ScheduleService;
import com.momentory.user.domain.UserProfile;
import com.momentory.user.infrastructure.UserProfileRepository;

/** 주간 리포트 재료를 여러 저장소에서 모은다. 읽기만 하고, 본문을 로그에 남기지 않는다. */
@Component
class WeeklyMaterialsReader {

    private final DiaryRepository diaryRepository;
    private final RetrospectRepository retrospectRepository;
    private final RetrospectStateCodec codec;
    private final DiaryEventCodec diaryEventCodec;
    private final ScheduleService scheduleService;
    private final RestRecordRepository restRecordRepository;
    private final UserProfileRepository userProfileRepository;
    private final SafetyPolicy safetyPolicy;

    WeeklyMaterialsReader(DiaryRepository diaryRepository, RetrospectRepository retrospectRepository,
            RetrospectStateCodec codec, DiaryEventCodec diaryEventCodec, ScheduleService scheduleService,
            RestRecordRepository restRecordRepository, UserProfileRepository userProfileRepository,
            SafetyPolicy safetyPolicy) {
        this.diaryRepository = diaryRepository;
        this.retrospectRepository = retrospectRepository;
        this.codec = codec;
        this.diaryEventCodec = diaryEventCodec;
        this.scheduleService = scheduleService;
        this.restRecordRepository = restRecordRepository;
        this.userProfileRepository = userProfileRepository;
        this.safetyPolicy = safetyPolicy;
    }

    /** 일기가 남은 가장 최근 주의 일요일 — {@code lastWeek} 까지만 본다. 일기가 전혀 없으면 {@code lastWeek}. */
    @Transactional(readOnly = true)
    public LocalDate latestWrittenWeek(Long userId, LocalDate lastWeek) {
        Instant before = DayBoundary.startOfDay(lastWeek.plusDays(7));
        return diaryRepository.findFirstByUserIdAndCreatedAtLessThanOrderByCreatedAtDesc(userId, before)
                .map(diary -> DayBoundary.toLocalDate(diary.getCreatedAt())
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)))
                .orElse(lastWeek);
    }

    @Transactional(readOnly = true)
    public WeeklyMaterials read(Long userId, LocalDate weekStart) {
        LocalDate weekEnd = weekStart.plusDays(6);
        List<WeeklyPages.Rest> soFar = restRecordRepository
                .findByUserIdAndCompletedTrueAndRecordDateLessThanEqualOrderByRecordDateAscIdAsc(userId, weekEnd)
                .stream().map(WeeklyMaterialsReader::rest).toList();
        return new WeeklyMaterials(userId, weekStart, weekEnd,
                userProfileRepository.findById(userId).map(UserProfile::getNickname).orElse(null),
                stories(userId, weekStart, weekEnd),
                scheduleService.getSchedulesInPeriod(userId, weekStart, weekEnd).stream()
                        .map(s -> new WeeklyPages.Task(s.id(), s.date(), s.title(), s.completed())).toList(),
                soFar.stream().filter(r -> !r.date().isBefore(weekStart)).toList(), soFar);
    }

    private static WeeklyPages.Rest rest(RestRecord record) {
        return new WeeklyPages.Rest(record.getContent(), record.getRecordDate(), record.getMood());
    }

    private List<DayStory> stories(Long userId, LocalDate from, LocalDate to) {
        // 최신순으로 오므로, 같은 날에 둘이 있으면 먼저 담기는 최신 것이 남는다(주간 리포트 v1 과 같다).
        Map<LocalDate, Diary> byDate = new LinkedHashMap<>();
        for (Diary diary : diaryRepository.findByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                userId, DayBoundary.startOfDay(from), DayBoundary.startOfDay(to.plusDays(1)))) {
            byDate.putIfAbsent(DayBoundary.toLocalDate(diary.getCreatedAt()), diary);
        }
        List<DayStory> stories = new ArrayList<>();
        byDate.forEach((date, diary) -> stories.add(story(date, diary)));
        stories.sort(Comparator.comparing(DayStory::date));
        return stories;
    }

    private DayStory story(LocalDate date, Diary diary) {
        Optional<RetrospectStateSnapshot> state = Optional.ofNullable(diary.getRetrospectId())
                .flatMap(retrospectRepository::findById)
                .map(r -> codec.deserialize(r.getStateJson()).toSnapshot());
        return state.map(s -> retrospectStory(date, diary, s)).orElseGet(() -> writtenStory(date, diary));
    }

    /** 회고로 만든 일기 — 일기 본문은 AI 글이라 인용하지 않고, 대화에서 사용자가 친 문장만 꺼낸다. */
    private DayStory retrospectStory(LocalDate date, Diary diary, RetrospectStateSnapshot state) {
        List<Message> messages = state.messages() == null ? List.of() : state.messages();
        List<String> userWords = messages.stream().filter(Message::isUser).map(Message::content)
                .filter(c -> c != null && !c.isBlank()).toList();
        // 「이어서 얘기하기」가 누적 안전 상태를 지우므로, 편지처럼 남은 안내 문구로 판정한다.
        boolean crisis = messages.stream().filter(Message::isAssistant).map(Message::content)
                .anyMatch(safetyPolicy::isGuidance);
        String event = retrospectEvent(diary.getPrimaryEmotion(), state);

        String letterAnswer = state.todayAction() == null || userWords.isEmpty() ? null
                : usableQuote(userWords.getFirst());
        if (letterAnswer != null) {
            return new DayStory(diary.getId(), date, diary.getPrimaryEmotion(), event, letterAnswer,
                    "편지에서 고른 " + state.todayAction().title(), true, crisis, state.scheduleId(),
                    diary.getOriginal(), userWords);
        }
        Quote quote = evidenceQuote(state, userWords);
        return new DayStory(diary.getId(), date, diary.getPrimaryEmotion(), event,
                quote == null ? null : quote.text(), quote == null || quote.event() == null ? event : quote.event(),
                false, crisis, state.scheduleId(), diary.getOriginal(), userWords);
    }

    private record Quote(String text, String event) {
    }

    /** 감정 근거로 뽑아 둔 사용자 문장 중 가장 센 것 — 실제로 대화에 있는 문장인지 한 번 더 대조한다. */
    private static Quote evidenceQuote(RetrospectStateSnapshot state, List<String> userWords) {
        List<ExtractedEmotion> emotions = state.emotions() == null ? List.of() : state.emotions();
        return emotions.stream()
                .filter(e -> usableQuote(e.evidence()) != null && LetterRules.isVerbatim(e.evidence(), userWords))
                .max(Comparator.comparingInt(e -> e.intensity() == null ? 0 : e.intensity()))
                .map(e -> new Quote(usableQuote(e.evidence()), eventLabel(state, e.eventId())))
                .orElse(null);
    }

    private static String retrospectEvent(Emotion primary, RetrospectStateSnapshot state) {
        List<ExtractedEmotion> emotions = state.emotions() == null ? List.of() : state.emotions();
        return emotions.stream().filter(e -> e.normalized() == primary && e.eventId() != null)
                .map(e -> eventLabel(state, e.eventId())).filter(Objects::nonNull).findFirst()
                .or(() -> (state.events() == null ? List.<ExtractedEvent>of() : state.events()).stream()
                        .map(ExtractedEvent::topicLabel).filter(Objects::nonNull).findFirst())
                .orElse(state.schedule());
    }

    private static String eventLabel(RetrospectStateSnapshot state, Integer eventId) {
        if (eventId == null || state.events() == null) {
            return null;
        }
        return state.events().stream().filter(e -> e.id() == eventId).map(ExtractedEvent::topicLabel)
                .filter(Objects::nonNull).findFirst().orElse(null);
    }

    /** 직접 쓴 일기 — 본문이 곧 사용자의 말이다. */
    private DayStory writtenStory(LocalDate date, Diary diary) {
        List<DiaryEvent> events = diaryEventCodec.deserialize(diary.getEventsJson());
        String event = events.stream().filter(e -> e.emotions().contains(diary.getPrimaryEmotion()))
                .map(DiaryEvent::label).findFirst()
                .or(() -> events.stream().map(DiaryEvent::label).findFirst()).orElse(null);
        return new DayStory(diary.getId(), date, diary.getPrimaryEmotion(), event,
                usableQuote(LetterRules.fallbackQuote(diary.getOriginal())), event, false, false, null,
                diary.getOriginal(), List.of());
    }

    private static String usableQuote(String text) {
        String value = text == null ? "" : LetterRules.withoutLeadingTimeWord(text.strip());
        return value.length() < WeeklyReportRules.QUOTE_MIN_LENGTH || value.length() > WeeklyReportRules.QUOTE_MAX_LENGTH
                ? null : value;
    }
}
