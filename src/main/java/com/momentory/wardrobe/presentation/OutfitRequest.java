package com.momentory.wardrobe.presentation;

import com.momentory.wardrobe.application.WardrobeResults.WardrobeOutfit;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** 입을 것 — 칸마다 아이템 id 하나, 벗으려면 null. 보낸 그대로 바뀐다. */
public record OutfitRequest(
        @Schema(description = "모자 아이템 id", example = "hat-bucket", nullable = true)
        @Size(max = 40, message = "아이템 id 가 너무 깁니다.") String hat,
        @Schema(description = "얼굴 아이템 id", example = "eyes-glasses", nullable = true)
        @Size(max = 40, message = "아이템 id 가 너무 깁니다.") String eyes,
        @Schema(description = "옷 아이템 id", example = "clothes-knit-vest", nullable = true)
        @Size(max = 40, message = "아이템 id 가 너무 깁니다.") String clothes
) {
    WardrobeOutfit toOutfit() {
        return new WardrobeOutfit(hat, eyes, clothes);
    }
}
