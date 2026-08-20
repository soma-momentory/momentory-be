package com.momentory.schedule.domain;

import com.momentory.common.persistence.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "schedules")
public class Schedule extends BaseTimeEntity {

    public static final int TITLE_MAX_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "external_id", length = 255)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleSource source;

    @Column(name = "schedule_date", nullable = false)
    private LocalDate scheduleDate;

    @Column(nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    @Column(nullable = false)
    private boolean completed;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ScheduleEmotion emotion;

    @Column(name = "display_order", nullable = false)
    private long displayOrder;

    @Column(nullable = false)
    private boolean hidden;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Schedule() {
    }

    private Schedule(
            Long userId,
            LocalDate scheduleDate,
            String title,
            long displayOrder,
            ScheduleSource source,
            String externalId
    ) {
        this.userId = Objects.requireNonNull(userId);
        this.scheduleDate = Objects.requireNonNull(scheduleDate);
        this.title = validateTitle(title);
        this.displayOrder = displayOrder;
        this.source = Objects.requireNonNull(source);
        this.externalId = validateExternalId(source, externalId);
        this.completed = false;
        this.hidden = false;
    }

    public static Schedule createManual(Long userId, LocalDate scheduleDate, String title, long displayOrder) {
        return new Schedule(userId, scheduleDate, title, displayOrder, ScheduleSource.MANUAL, null);
    }

    public static Schedule createCalendar(
            Long userId,
            String externalId,
            LocalDate scheduleDate,
            String title,
            long displayOrder
    ) {
        return new Schedule(userId, scheduleDate, title, displayOrder, ScheduleSource.CALENDAR, externalId);
    }

    public void update(LocalDate scheduleDate, String title) {
        this.scheduleDate = Objects.requireNonNull(scheduleDate);
        this.title = validateTitle(title);
    }

    public void delete(Instant deletedAt) {
        if (this.deletedAt == null) {
            this.deletedAt = Objects.requireNonNull(deletedAt);
        }
    }

    public boolean syncFromCalendar(LocalDate scheduleDate, String title) {
        if (source != ScheduleSource.CALENDAR) {
            throw new IllegalStateException("only calendar schedules can be synchronized");
        }
        LocalDate normalizedDate = Objects.requireNonNull(scheduleDate);
        String normalizedTitle = validateTitle(title);
        boolean changed = !this.scheduleDate.equals(normalizedDate)
                || !this.title.equals(normalizedTitle)
                || this.deletedAt != null;
        this.scheduleDate = normalizedDate;
        this.title = normalizedTitle;
        this.deletedAt = null;
        return changed;
    }

    public void changeHidden(boolean hidden) {
        if (source != ScheduleSource.CALENDAR) {
            throw new IllegalStateException("only calendar schedules can be hidden");
        }
        this.hidden = hidden;
    }

    public void changeCompletion(boolean completed, ScheduleEmotion emotion) {
        if (!completed && emotion != null) {
            throw new IllegalArgumentException("emotion must be null when schedule is not completed");
        }
        this.completed = completed;
        this.emotion = completed ? emotion : null;
    }

    public void changeDisplayOrder(long displayOrder) {
        this.displayOrder = displayOrder;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getScheduleDate() {
        return scheduleDate;
    }

    public String getTitle() {
        return title;
    }

    public boolean isCompleted() {
        return completed;
    }

    public ScheduleEmotion getEmotion() {
        return emotion;
    }

    public boolean belongsTo(Long userId) {
        return this.userId.equals(userId);
    }

    public String getExternalId() {
        return externalId;
    }

    public ScheduleSource getSource() {
        return source;
    }

    public boolean isHidden() {
        return hidden;
    }

    public long getDisplayOrder() {
        return displayOrder;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    private String validateTitle(String title) {
        String normalizedTitle = Objects.requireNonNull(title).strip();
        if (normalizedTitle.isBlank() || normalizedTitle.length() > TITLE_MAX_LENGTH) {
            throw new IllegalArgumentException("title must be between 1 and 255 characters");
        }
        return normalizedTitle;
    }

    private String validateExternalId(ScheduleSource source, String externalId) {
        if (source == ScheduleSource.MANUAL) {
            if (externalId != null) {
                throw new IllegalArgumentException("manual schedule must not have an external id");
            }
            return null;
        }
        String normalizedExternalId = Objects.requireNonNull(externalId).strip();
        if (normalizedExternalId.isBlank() || normalizedExternalId.length() > 255) {
            throw new IllegalArgumentException("external id must be between 1 and 255 characters");
        }
        return normalizedExternalId;
    }
}
