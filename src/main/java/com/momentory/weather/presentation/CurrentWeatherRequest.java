package com.momentory.weather.presentation;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonCreator;
import tools.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record CurrentWeatherRequest(
        @NotNull @DecimalMin("-90") @DecimalMax("90") @Schema(example = "37.57") BigDecimal latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") @Schema(example = "126.98") BigDecimal longitude) {
    @JsonCreator
    public static CurrentWeatherRequest fromJson(JsonNode latitude, JsonNode longitude) {
        if ((latitude != null && !latitude.isNull() && !latitude.isNumber())
                || (longitude != null && !longitude.isNull() && !longitude.isNumber()))
            throw new IllegalArgumentException("좌표는 숫자여야 합니다.");
        return new CurrentWeatherRequest(latitude == null || latitude.isNull() ? null : latitude.decimalValue(),
                longitude == null || longitude.isNull() ? null : longitude.decimalValue());
    }
}
