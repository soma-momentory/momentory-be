package com.momentory.weather.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.momentory.weather.application.WeatherUpstreamException;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MetForecastClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sendsRoundedCoordinatesAndCachesMinimalForecast() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            Instant now = Instant.now();
            server.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                    .setBody(json(now, "heavyrainshowersandthunder_night", "21.4", "11.2")));
            var client = new MetForecastClient(server.url("/").toString(), ObservationRegistry.NOOP);
            var first = client.forecast(37.56789, 126.97891);
            var second = client.forecast(37.56789, 126.97891);
            assertThat(first).isEqualTo(second);
            assertThat(first.condition().code()).isEqualTo("rain.heavy.showers.thunder.night");
            assertThat(first.condition().windy()).isTrue();
            assertThat(first.temperatureC()).isEqualTo(21);
            assertThat(first.updatedAt()).isEqualTo(now);
            assertThat(server.getRequestCount()).isEqualTo(1);
            var request = server.takeRequest();
            assertThat(request.getPath()).isEqualTo("/weatherapi/locationforecast/2.0/compact?lat=37.57&lon=126.98");
            assertThat(request.getHeader("User-Agent")).contains("momentory.co.kr");
        }
    }

    @Test
    void rejectsMalformedForecastAndDoesNotCacheFailure() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().addHeader("Content-Type", "application/json").setBody("{}"));
            server.enqueue(new MockResponse().setResponseCode(500));
            var client = new MetForecastClient(server.url("/").toString(), ObservationRegistry.NOOP);
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> client.forecast(37.57, 126.98))
                        .isInstanceOf(WeatherUpstreamException.class).hasMessage(null).hasNoCause();
            }
            assertThat(server.getRequestCount()).isEqualTo(2);
        }
    }

    @Test
    void allowsMissingTemperatureAndUnknownFutureSymbol() {
        Instant now = Instant.parse("2026-10-15T13:00:00Z");
        var result = MetForecastClient.parseForecast(mapper.readTree(json(now, "futureweather_day", "null", "0")), now);
        assertThat(result.temperatureC()).isNull();
        assertThat(result.condition().family()).isEqualTo("unknown");
        assertThat(result.forecastTime()).isEqualTo(now);
    }

    @Test
    void expiredProviderResponseIsNotCached() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            Instant now = Instant.now();
            for (int i = 0; i < 2; i++) {
                server.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                        .addHeader("Expires", "Wed, 21 Oct 2015 07:28:00 GMT")
                        .setBody(json(now, "clearsky_day", "20", "0")));
            }
            var client = new MetForecastClient(server.url("/").toString(), ObservationRegistry.NOOP);
            client.forecast(37.57, 126.98);
            client.forecast(37.57, 126.98);
            assertThat(server.getRequestCount()).isEqualTo(2);
        }
    }

    @Test
    void doesNotPutCoordinatesInClientMetrics() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(500));
            var meters = new SimpleMeterRegistry();
            var observations = ObservationRegistry.create();
            observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
            var client = new MetForecastClient(server.url("/").toString(), observations);
            assertThatThrownBy(() -> client.forecast(37.56789, 126.97891))
                    .isInstanceOf(WeatherUpstreamException.class);
            var timer = meters.get("http.client.requests").tag("status", "500").timer();
            assertThat(timer.getId().getTag("uri")).doesNotContain("37.57", "126.98");
        }
    }

    @Test
    void classifiesFiveSecondProviderTimeout() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setHeadersDelay(6, TimeUnit.SECONDS)
                    .addHeader("Content-Type", "application/json")
                    .setBody(json(Instant.now(), "clearsky_day", "20", "0")));
            var client = new MetForecastClient(server.url("/").toString(), ObservationRegistry.NOOP);
            assertThatThrownBy(() -> client.forecast(37.57, 126.98))
                    .isInstanceOfSatisfying(WeatherUpstreamException.class,
                            error -> assertThat(error.reason()).isEqualTo(WeatherUpstreamException.Reason.TIMEOUT));
        }
    }

    @Test
    void backsOffAfterProviderRateLimit() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(429));
            var client = new MetForecastClient(server.url("/").toString(), ObservationRegistry.NOOP);
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> client.forecast(37.57, 126.98))
                        .isInstanceOf(WeatherUpstreamException.class);
            }
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    private String json(Instant time, String symbol, String temperature, String windSpeed) {
        return """
                {"properties":{"meta":{"updated_at":"%s"},"timeseries":[{"time":"%s","data":{
                  "instant":{"details":{"air_temperature":%s,"wind_speed":%s}},
                  "next_1_hours":{"summary":{"symbol_code":"%s"}}
                }}]}}
                """.formatted(time, time, temperature, windSpeed, symbol);
    }
}
