package com.momentory.diary.infrastructure;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import com.momentory.diary.application.WeatherProvider;
import com.momentory.diary.application.WeatherUnavailableException;

/** MET Norway의 해당 시각 예보. 키 불필요, 상업 사용 가능(CC BY 4.0). */
@Component
public class MetWeatherProvider implements WeatherProvider {
    private final RestClient client;
    private record Cached(String weather, Instant expiresAt) {}
    private final Map<String, Cached> cache = new LinkedHashMap<>();


    public MetWeatherProvider(
            @Value("${weather.base-url:https://api.met.no}") String baseUrl,
            ObservationRegistry observationRegistry) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .observationRegistry(observationRegistry)
                .defaultHeader("User-Agent", "Momentory/1.2 (https://momentory.co.kr)")
                .build();
    }

    @Override
    public String current(double latitude, double longitude) {
        try {
            // 0.01도 단위로 보내 정확한 기기 위치를 외부 제공자에 전달하지 않는다.
            String lat = String.format(Locale.ROOT, "%.2f", latitude);
            String lon = String.format(Locale.ROOT, "%.2f", longitude);
            String key = lat + "," + lon;
            synchronized (cache) {
                Cached cached = cache.get(key);
                if (cached != null && cached.expiresAt().isAfter(Instant.now())) return cached.weather();
            }
            var response = client.get().uri(uri -> uri
                    .path("/weatherapi/locationforecast/2.0/compact")
                    .queryParam("lat", lat).queryParam("lon", lon).build())
                    .retrieve().toEntity(JsonNode.class);
            Instant now = Instant.now();
            String weather = parse(response.getBody(), now);
            Instant expiresAt = now.plusSeconds(300);
            String expires = response.getHeaders().getFirst("Expires");
            if (expires != null) {
                try {
                    expiresAt = ZonedDateTime.parse(expires, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                } catch (java.time.format.DateTimeParseException ignored) {
                    // 비표준 헤더는 짧은 기본 캐시 시간으로 처리한다.
                }
            }
            // 같은 좌표 요청을 재사용하되 오래된 시각의 예보를 기록하지 않는다.
            if (expiresAt.isAfter(now.plusSeconds(1800))) expiresAt = now.plusSeconds(1800);
            synchronized (cache) {
                cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
                if (cache.size() >= 512) cache.remove(cache.keySet().iterator().next());
                cache.put(key, new Cached(weather, expiresAt));
            }
            return weather;
        } catch (RuntimeException ignored) {
            // 예외 원문에는 요청 좌표가 들어갈 수 있으므로 로그/응답에 싣지 않는다.
            throw new WeatherUnavailableException();
        }
    }

    static String parse(JsonNode response, Instant now) {
        if (response == null) throw new WeatherUnavailableException();
        JsonNode times = response.path("properties").path("timeseries");
        JsonNode nearest = null;
        long distance = Long.MAX_VALUE;
        for (JsonNode item : times) {
            long delta = Math.abs(Duration.between(Instant.parse(item.path("time").asText()), now).toSeconds());
            if (delta < distance) {
                nearest = item;
                distance = delta;
            }
        }
        if (nearest == null || distance > 3600) throw new WeatherUnavailableException();
        JsonNode data = nearest.path("data");
        JsonNode temperature = data.path("instant").path("details").path("air_temperature");
        if (!temperature.isNumber() || !Double.isFinite(temperature.asDouble())
                || temperature.asDouble() < -100 || temperature.asDouble() > 70) {
            throw new WeatherUnavailableException();
        }
        String symbol = data.path("next_1_hours").path("summary").path("symbol_code").asText("");
        if (symbol.isEmpty()) symbol = data.path("next_6_hours").path("summary").path("symbol_code").asText("");
        String description = label(symbol);
        return description + " · " + Math.round(temperature.asDouble()) + "℃";
    }

    static String label(String symbol) {
        if (symbol.contains("thunder")) return "뇌우";
        if (symbol.contains("sleet")) return "진눈깨비";
        if (symbol.contains("snow")) return "눈";
        if (symbol.contains("rain")) return "비";
        return switch (symbol.replaceAll("_(day|night|polartwilight)$", "")) {
            case "clearsky" -> "맑음";
            case "fair" -> "대체로 맑음";
            case "partlycloudy" -> "구름 조금";
            case "cloudy" -> "흐림";
            case "fog" -> "안개";
            default -> throw new WeatherUnavailableException();
        };
    }
}
