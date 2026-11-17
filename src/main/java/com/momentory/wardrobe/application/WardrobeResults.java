package com.momentory.wardrobe.application;

import com.momentory.wardrobe.domain.ItemTier;
import com.momentory.wardrobe.domain.TangerineReason;
import com.momentory.wardrobe.domain.WardrobeSlot;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 옷장 API 응답들. */
public final class WardrobeResults {

    private WardrobeResults() {
    }

    public record WardrobeView(
            @Schema(description = "가진 귤", example = "120") int tangerines,
            WardrobeOutfit outfit,
            @Schema(description = "상점에 보일 아이템 — 무료 · 귤로 사는 것 · 이미 가진 것. 그림과 이름은 앱이 가진다")
            List<WardrobeItem> items,
            @Schema(description = "오늘(04:00 경계) 날짜로 이미 받은 보상 사유 — 오늘의 미션에 받은 줄을 표시한다")
            List<TangerineReason> todayRewards,
            @Schema(description = "출석 이레 중 오늘 칸(1~7). 날마다 이어 오면 하나씩 넘어가고, 이레째 다음이나 하루를 거르면 1", example = "3")
            int attendanceDay,
            @Schema(description = "출석 칸마다 받는 귤(일곱 개)", example = "[10, 10, 20, 20, 30, 40, 50]")
            List<Integer> attendanceRewards,
            @Schema(description = "옷장 첫 선물을 이미 받았는가")
            boolean welcomed
    ) {
    }

    public record WardrobeOutfit(
            @Schema(description = "모자 아이템 id, 없으면 null", example = "hat-bucket") String hat,
            @Schema(description = "얼굴 아이템 id, 없으면 null", example = "eyes-glasses") String eyes,
            @Schema(description = "옷 아이템 id, 없으면 null", example = "clothes-knit-vest") String clothes
    ) {
        public static final WardrobeOutfit EMPTY = new WardrobeOutfit(null, null, null);
    }

    public record WardrobeItem(
            @Schema(description = "「칸-아이디」 — 앱 레이어 파일 이름과 같다", example = "hat-bucket") String id,
            WardrobeSlot slot,
            ItemTier tier,
            @Schema(description = "귤 값. 무료는 0", example = "250") int price,
            @Schema(description = "입을 수 있는가(무료이거나 샀다)") boolean owned
    ) {
    }

    public record TangerineCheckIn(
            @Schema(description = "정산 뒤 가진 귤", example = "160") int tangerines,
            @Schema(description = "이번에 새로 받은 귤. 받을 게 없으면 빈 목록") List<TangerineReward> rewards,
            @Schema(description = "오늘 위기 안내가 있었는가 — 참이면 앱은 받은 귤을 알리지 않는다")
            boolean quiet
    ) {
    }

    public record TangerineReward(TangerineReason reason, @Schema(example = "30") int amount) {
    }

    public record PurchaseResult(@Schema(description = "산 뒤 남은 귤", example = "50") int tangerines) {
    }
}
