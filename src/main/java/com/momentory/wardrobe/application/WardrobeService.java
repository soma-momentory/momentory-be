package com.momentory.wardrobe.application;

import com.momentory.common.time.DayBoundary;
import com.momentory.diary.application.DiaryQueryService;
import com.momentory.schedule.application.ScheduleResult;
import com.momentory.schedule.application.ScheduleService;
import com.momentory.wardrobe.application.WardrobeResults.TangerineCheckIn;
import com.momentory.wardrobe.application.WardrobeResults.WardrobeItem;
import com.momentory.wardrobe.application.WardrobeResults.WardrobeOutfit;
import com.momentory.wardrobe.application.WardrobeResults.PurchaseResult;
import com.momentory.wardrobe.application.WardrobeResults.TangerineReward;
import com.momentory.wardrobe.application.WardrobeResults.WardrobeView;
import com.momentory.wardrobe.domain.Attendance;
import com.momentory.wardrobe.domain.ItemTier;
import com.momentory.wardrobe.domain.TangerineReason;
import com.momentory.wardrobe.domain.WardrobeSlot;
import com.momentory.wardrobe.infrastructure.WardrobeRepository;
import com.momentory.wardrobe.infrastructure.WardrobeRepository.ItemRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 바바 꾸미기 — 옷장 조회 · 입히기 · 귤로 사기 · 귤 정산.
 *
 * <p>귤은 앱이 「주세요」라고 요청해서 주지 않는다. 앱이 정산을 부르면 서버가 일기 · 일정이 실제로 있는지
 * 보고 준다. 일기 · 일정 · 쉼터 · 편지 코드를 건드리지 않으려고, 저장할 때가 아니라 정산할 때 확인한다.
 * 새벽 4시 언저리에 쓴 일기도 놓치지 않게 어제와 오늘을 함께 본다. 출석만은 오늘 것만 준다.
 */
@Service
public class WardrobeService {

    private final WardrobeRepository repository;
    private final DiaryQueryService diaries;
    private final ScheduleService schedules;

    public WardrobeService(WardrobeRepository repository, DiaryQueryService diaries, ScheduleService schedules) {
        this.repository = repository;
        this.diaries = diaries;
        this.schedules = schedules;
    }

    @Transactional(readOnly = true)
    public WardrobeView wardrobe(Long userId) {
        List<WardrobeItem> items = repository.shopItems(userId).stream()
                .map(row -> new WardrobeItem(row.id(), row.slot(), row.tier(), row.price(), row.owned()))
                .toList();
        LocalDate today = DayBoundary.today();
        return new WardrobeView(repository.balance(userId), repository.outfit(userId), items,
                repository.rewardReasonsOn(userId, today),
                Attendance.dayOf(checkInDays(userId), today), Attendance.REWARDS,
                !repository.rewardDays(userId, TangerineReason.WELCOME).isEmpty());
    }

    /** 옷장에 처음 온 선물 — 평생 한 번. 이미 받았으면 빈 목록이다. */
    @Transactional
    public TangerineCheckIn welcome(Long userId) {
        List<TangerineReward> rewards = new ArrayList<>();
        reward(userId, TangerineReason.WELCOME, DayBoundary.today(), rewards);
        return new TangerineCheckIn(repository.balance(userId), List.copyOf(rewards), false);
    }

    @Transactional
    public WardrobeOutfit wear(Long userId, WardrobeOutfit outfit) {
        requireWearable(userId, outfit.hat(), WardrobeSlot.HAT);
        requireWearable(userId, outfit.eyes(), WardrobeSlot.EYES);
        requireWearable(userId, outfit.clothes(), WardrobeSlot.CLOTHES);
        repository.saveOutfit(userId, outfit);
        return outfit;
    }

    /** 이미 가졌으면 귤을 쓰지 않고 지금 잔액을 돌려준다 — 두 번 눌러도 한 번만 산다. */
    @Transactional
    public PurchaseResult purchase(Long userId, String itemId) {
        ItemRow item = repository.item(userId, itemId).orElseThrow(WardrobeException::itemNotFound);
        if (item.owned()) {
            return new PurchaseResult(repository.balance(userId));
        }
        if (item.tier() != ItemTier.TANGERINE || !item.onSale()) {
            throw WardrobeException.itemNotFound();
        }
        if (repository.own(userId, itemId) && !repository.spend(userId, itemId, item.price())) {
            throw WardrobeException.notEnoughTangerines(); // 예외로 위의 own 까지 되돌린다
        }
        return new PurchaseResult(repository.balance(userId));
    }

    @Transactional
    public TangerineCheckIn checkIn(Long userId) {
        LocalDate today = DayBoundary.today();
        List<TangerineReward> rewards = new ArrayList<>();
        reward(userId, TangerineReason.CHECK_IN, today,
                Attendance.reward(Attendance.dayOf(checkInDays(userId), today)), rewards);
        for (LocalDate day : List.of(today.minusDays(1), today)) {
            if (diaries.hasDiaryOn(userId, day)) {
                reward(userId, TangerineReason.DIARY, day, rewards);
            }
            if (allSchedulesDone(userId, day)) {
                reward(userId, TangerineReason.SCHEDULES_DONE, day, rewards);
            }
            if (repository.visitedRest(userId, day)) {
                reward(userId, TangerineReason.REST, day, rewards);
            }
            if (repository.openedLetter(userId, DayBoundary.startOfDay(day), DayBoundary.startOfDay(day.plusDays(1)))) {
                reward(userId, TangerineReason.LETTER, day, rewards);
            }
        }
        boolean quiet = repository.hadSafetyEventSince(userId, DayBoundary.startOfDay(today));
        return new TangerineCheckIn(repository.balance(userId), List.copyOf(rewards), quiet);
    }

    private void reward(Long userId, TangerineReason reason, LocalDate day, List<TangerineReward> rewards) {
        reward(userId, reason, day, reason.amount(), rewards);
    }

    private void reward(Long userId, TangerineReason reason, LocalDate day, int amount, List<TangerineReward> rewards) {
        if (repository.reward(userId, reason, day, amount)) rewards.add(new TangerineReward(reason, amount));
    }

    private Set<LocalDate> checkInDays(Long userId) {
        return new HashSet<>(repository.rewardDays(userId, TangerineReason.CHECK_IN));
    }

    /** 숨긴 캘린더 일정은 세지 않는다. 일정이 하나도 없으면 받지 않는다. */
    private boolean allSchedulesDone(Long userId, LocalDate day) {
        List<ScheduleResult> visible = schedules.getSchedules(userId, day).stream()
                .filter(schedule -> !schedule.hidden())
                .toList();
        return !visible.isEmpty() && visible.stream().allMatch(ScheduleResult::completed);
    }

    private void requireWearable(Long userId, String itemId, WardrobeSlot slot) {
        if (itemId == null) return;
        ItemRow item = repository.item(userId, itemId)
                .filter(row -> row.slot() == slot)
                .orElseThrow(WardrobeException::itemNotFound);
        if (!item.owned()) throw WardrobeException.itemNotOwned();
    }
}
