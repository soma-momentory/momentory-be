package com.momentory.weather.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CurrentWeatherRequestTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsNumbersAndIgnoresOtherFields() {
        var request = mapper.readValue("{\"latitude\":37.57,\"longitude\":126.98,\"extra\":true}",
                CurrentWeatherRequest.class);
        assertThat(request.latitude().doubleValue()).isEqualTo(37.57);
        assertThat(request.longitude().doubleValue()).isEqualTo(126.98);
    }

    @Test
    void rejectsStringCoordinates() {
        assertThatThrownBy(() -> mapper.readValue("{\"latitude\":\"37.57\",\"longitude\":126.98}",
                CurrentWeatherRequest.class)).isInstanceOf(RuntimeException.class);
    }
}
