package com.momentory.diary.presentation;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

public record DiaryWeatherRequest(
        @NotNull(message = "위도를 입력해주세요.")
        @DecimalMin(value = "-90", message = "위도는 -90부터 90까지입니다.")
        @DecimalMax(value = "90", message = "위도는 -90부터 90까지입니다.")
        @Schema(example = "37.57") BigDecimal latitude,
        @NotNull(message = "경도를 입력해주세요.")
        @DecimalMin(value = "-180", message = "경도는 -180부터 180까지입니다.")
        @DecimalMax(value = "180", message = "경도는 -180부터 180까지입니다.")
        @Schema(example = "126.98") BigDecimal longitude) {
}
