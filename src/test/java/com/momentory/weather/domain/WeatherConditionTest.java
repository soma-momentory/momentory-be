package com.momentory.weather.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WeatherConditionTest {
    @Test
    void normalizesDayNightIntensityAndCombinedWeather() {
        var rain = WeatherCondition.from("heavyrainshowersandthunder_night", 12.0);
        assertThat(rain.code()).isEqualTo("rain.heavy.showers.thunder.night");
        assertThat(rain.family()).isEqualTo("rain");
        assertThat(rain.intensity()).isEqualTo("heavy");
        assertThat(rain.showers()).isTrue();
        assertThat(rain.thunder()).isTrue();
        assertThat(rain.daylight()).isEqualTo("night");
        assertThat(rain.windy()).isTrue();
        assertThat(rain.label()).isEqualTo("강한 소나기 · 뇌우");
        assertThat(WeatherCondition.from("clearsky_polartwilight", null).code()).isEqualTo("clear.day");
        assertThat(WeatherCondition.from("partlycloudy_night", null).code()).isEqualTo("partly_cloudy.night");
        assertThat(WeatherCondition.from("lightssleetshowersandthunder_day", null).code())
                .isEqualTo("sleet.light.showers.thunder.day");
        assertThat(WeatherCondition.from("lightssnowshowersandthunder_night", null).code())
                .isEqualTo("snow.light.showers.thunder.night");
        assertThat(WeatherCondition.from("newweather_day", null).family()).isEqualTo("unknown");
    }
}
