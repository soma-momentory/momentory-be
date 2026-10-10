package com.momentory.diary.infrastructure;

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import com.momentory.diary.application.WeatherUnavailableException;
import static org.assertj.core.api.Assertions.*;

class MetWeatherProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void callsProviderWithCoarseCoordinatesAndCachesResponse() throws Exception {
        try (var server = new okhttp3.mockwebserver.MockWebServer()) {
            server.start();
            server.enqueue(new okhttp3.mockwebserver.MockResponse()
                    .addHeader("Content-Type", "application/json")
                    .setBody("""
                            {"properties":{"timeseries":[{"time":"%s","data":{
                              "instant":{"details":{"air_temperature":21.6}},
                              "next_1_hours":{"summary":{"symbol_code":"clearsky_day"}}
                            }}]}}
                            """.formatted(Instant.now().toString())));
            MetWeatherProvider provider = new MetWeatherProvider(server.url("/").toString(), ObservationRegistry.NOOP);
            assertThat(provider.current(37.56789, 126.97891)).isEqualTo("맑음 · 22℃");
            assertThat(provider.current(37.56789, 126.97891)).isEqualTo("맑음 · 22℃");
            assertThat(server.getRequestCount()).isEqualTo(1);
            var request = server.takeRequest();
            assertThat(request.getPath()).isEqualTo("/weatherapi/locationforecast/2.0/compact?lat=37.57&lon=126.98");
            assertThat(request.getHeader("User-Agent")).contains("momentory.co.kr");
        }
    }

    @Test
    void hidesProviderFailureAndDoesNotCacheIt() throws Exception {
        try (var server = new okhttp3.mockwebserver.MockWebServer()) {
            server.start();
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(503));
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(503));
            MetWeatherProvider provider = new MetWeatherProvider(server.url("/").toString(), ObservationRegistry.NOOP);
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> provider.current(37.57, 126.98))
                        .isInstanceOf(WeatherUnavailableException.class).hasMessage(null).hasNoCause();
            }
            assertThat(server.getRequestCount()).isEqualTo(2);
        }
    }

    @Test
    void recordsCallAsClientMetricWithoutCoordinates() throws Exception {
        try (var server = new okhttp3.mockwebserver.MockWebServer()) {
            server.start();
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(503));
            var meters = new SimpleMeterRegistry();
            var observations = ObservationRegistry.create();
            observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
            MetWeatherProvider provider = new MetWeatherProvider(server.url("/").toString(), observations);
            assertThatThrownBy(() -> provider.current(37.56789, 126.97891))
                    .isInstanceOf(WeatherUnavailableException.class);
            var timer = meters.get("http.client.requests").tag("status", "503").timer();
            assertThat(timer.count()).isEqualTo(1);
            // 위치가 태그로 나가면 외부 모니터링에 사용자 위치가 남고 시계열도 좌표마다 늘어난다.
            assertThat(timer.getId().getTag("uri")).doesNotContain("37.57", "126.98");
        }
    }

    @Test
    void parsesClosestForecastAndTemperature() {
        var data = mapper.readTree("""
                {"properties":{"timeseries":[{"time":"2026-10-07T04:00:00Z","data":{
                  "instant":{"details":{"air_temperature":21.6}},
                  "next_1_hours":{"summary":{"symbol_code":"clearsky_day"}}
                }}]}}
                """);
        assertThat(MetWeatherProvider.parse(data, Instant.parse("2026-10-07T04:15:00Z")))
                .isEqualTo("맑음 · 22℃");
        assertThatThrownBy(() -> MetWeatherProvider.parse(data, Instant.parse("2026-10-08T04:00:00Z")))
                .isInstanceOf(WeatherUnavailableException.class);
        assertThatThrownBy(() -> MetWeatherProvider.parse(mapper.readTree("{}"), Instant.now()))
                .isInstanceOf(WeatherUnavailableException.class);
    }

    @Test
    void mapsPrecipitationWithoutInventingUnknownWeather() {
        assertThat(MetWeatherProvider.label("rainshowersandthunder_day")).isEqualTo("뇌우");
        assertThat(MetWeatherProvider.label("lightsleet")).isEqualTo("진눈깨비");
        assertThat(MetWeatherProvider.label("snow")).isEqualTo("눈");
        assertThat(MetWeatherProvider.label("rain")).isEqualTo("비");
        assertThat(MetWeatherProvider.label("partlycloudy_night")).isEqualTo("구름 조금");
        assertThatThrownBy(() -> MetWeatherProvider.label("unknown")).isInstanceOf(WeatherUnavailableException.class);
    }
}
