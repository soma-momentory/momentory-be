package com.momentory.weather.infrastructure;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.momentory.weather.domain.CurrentWeather;
import com.momentory.weather.domain.WeatherCondition;
import com.momentory.weather.application.WeatherUpstreamException;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/** 홈 날씨 조회용 MET Norway Locationforecast 클라이언트. */
@Component
public class MetForecastClient {
    private static final String PATH = "/weatherapi/locationforecast/2.0/compact";
    private final RestClient client;
    private volatile Instant backoffUntil = Instant.EPOCH;
    private long requestSecond;
    private int requestCount;
    // 사용자 정보와 MET 원본 응답은 보관하지 않는다. Caffeine이 만료 항목을 자체 제거한다.
    private final Cache<String, Cached> cache = Caffeine.newBuilder()
            .maximumSize(512).scheduler(Scheduler.systemScheduler())
            .expireAfter(Expiry.<String, Cached>creating((key, value) -> {
                Duration remaining = Duration.between(Instant.now(), value.expiresAt());
                return remaining.isNegative() ? Duration.ZERO : remaining;
            })).build();

    private record Cached(CurrentWeather weather, Instant expiresAt) {}

    public MetForecastClient(@Value("${weather.base-url:https://api.met.no}") String baseUrl,
            ObservationRegistry observationRegistry) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .observationRegistry(observationRegistry)
                .defaultHeader("User-Agent", "Momentory/1.2 (https://momentory.co.kr)")
                .build();
    }

    public CurrentWeather forecast(double latitude, double longitude) {
        String lat = String.format(Locale.ROOT, "%.2f", latitude);
        String lon = String.format(Locale.ROOT, "%.2f", longitude);
        String key = lat + "," + lon;
        Instant now = Instant.now();
        Cached cached = cache.getIfPresent(key);
        if (cached != null && cached.expiresAt().isAfter(now)) return cached.weather();
        if (backoffUntil.isAfter(now) || !permitMetRequest(now))
            throw new WeatherUpstreamException(WeatherUpstreamException.Reason.FAILED);
        try {
            // URI 템플릿을 유지해 관측 태그에 실제 좌표가 노출되지 않도록 한다.
            var response = client.get().uri(PATH + "?lat={lat}&lon={lon}", lat, lon)
                    .retrieve().toEntity(JsonNode.class);
            CurrentWeather weather = parseForecast(response.getBody(), now);
            Instant expiry = now.plus(Duration.ofMinutes(30));
            String expires = response.getHeaders().getFirst("Expires");
            if (expires != null) {
                try {
                    expiry = ZonedDateTime.parse(expires, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                } catch (DateTimeParseException ignored) {
                    // 비표준 Expires는 기본 30분을 쓴다.
                }
            }
            expiry = expiry.isAfter(now.plus(Duration.ofMinutes(60)))
                    ? now.plus(Duration.ofMinutes(60)) : expiry;
            if (expiry.isAfter(now)) cache.put(key, new Cached(weather, expiry));
            return weather;
        } catch (ResourceAccessException exception) {
            Throwable cause = exception;
            while (cause != null) {
                if (cause instanceof HttpTimeoutException || cause instanceof java.net.SocketTimeoutException)
                    throw new WeatherUpstreamException(WeatherUpstreamException.Reason.TIMEOUT);
                cause = cause.getCause();
            }
            throw new WeatherUpstreamException(WeatherUpstreamException.Reason.FAILED);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 403 || status == 429) backoffUntil = Instant.now().plus(Duration.ofMinutes(1));
            throw new WeatherUpstreamException(WeatherUpstreamException.Reason.FAILED);
        } catch (RestClientException | IllegalArgumentException ignored) {
            throw new WeatherUpstreamException(WeatherUpstreamException.Reason.FAILED);
        }
    }

    private synchronized boolean permitMetRequest(Instant now) {
        long second = now.getEpochSecond();
        if (second != requestSecond) {
            requestSecond = second;
            requestCount = 0;
        }
        return ++requestCount <= 20;
    }

    static CurrentWeather parseForecast(JsonNode response, Instant now) {
        if (response == null) throw new WeatherUpstreamException(WeatherUpstreamException.Reason.FAILED);
        try {
            JsonNode properties = response.path("properties");
            Instant updatedAt = Instant.parse(properties.path("meta").path("updated_at").asText());
            JsonNode nearest = null;
            long distance = Long.MAX_VALUE;
            for (JsonNode item : properties.path("timeseries")) {
                long delta = Math.abs(Duration.between(Instant.parse(item.path("time").asText()), now).toSeconds());
                if (delta < distance) {
                    nearest = item;
                    distance = delta;
                }
            }
            if (nearest == null || distance > 3600) throw new IllegalArgumentException();
            JsonNode data = nearest.path("data");
            JsonNode temperature = data.path("instant").path("details").path("air_temperature");
            Integer temp = temperature.isNumber() && Double.isFinite(temperature.asDouble())
                    && temperature.asDouble() >= -100 && temperature.asDouble() <= 70
                    ? (int) Math.round(temperature.asDouble()) : null;
            JsonNode wind = data.path("instant").path("details").path("wind_speed");
            Double windSpeed = wind.isNumber() ? wind.asDouble() : null;
            JsonNode symbolNode = data.path("next_1_hours").path("summary").path("symbol_code");
            if (symbolNode.isMissingNode() || (symbolNode.isTextual() && symbolNode.asText().isBlank()))
                symbolNode = data.path("next_6_hours").path("summary").path("symbol_code");
            if (!symbolNode.isTextual() || symbolNode.asText().isBlank()) throw new IllegalArgumentException();
            String symbol = symbolNode.asText();
            WeatherCondition condition = WeatherCondition.from(symbol, windSpeed);
            return new CurrentWeather(condition, symbol, condition.label(), temp,
                    Instant.parse(nearest.path("time").asText()), updatedAt);
        } catch (RuntimeException ignored) {
            throw new WeatherUpstreamException(WeatherUpstreamException.Reason.FAILED);
        }
    }

}
