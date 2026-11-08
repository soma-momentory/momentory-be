package com.momentory.weather.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;
import com.momentory.weather.infrastructure.MetForecastClient;
import com.momentory.weather.domain.CurrentWeather;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CurrentWeatherService {
    private final MetForecastClient provider;
    private final boolean enabled;
    // 제한 계수는 메모리에 1분만 보관한다. 사용자 좌표는 이 저장소에 없다.
    private final Cache<Long, Integer> requests;

    @Autowired
    public CurrentWeatherService(MetForecastClient provider,
            @Value("${weather.current.enabled:true}") boolean enabled) {
        this(provider, enabled, Ticker.systemTicker());
    }

    CurrentWeatherService(MetForecastClient provider, boolean enabled, Ticker ticker) {
        this.provider = provider;
        this.enabled = enabled;
        this.requests = Caffeine.newBuilder().maximumSize(100_000).ticker(ticker)
                .scheduler(Scheduler.systemScheduler())
                .expireAfter(Expiry.<Long, Integer>creating((id, count) -> Duration.ofMinutes(1)))
                .build();
    }

    public CurrentWeather current(long userId, double latitude, double longitude) {
        if (!enabled) throw new WeatherDisabledException();
        int count = requests.asMap().compute(userId, (id, previous) -> previous == null ? 1 : Math.min(31, previous + 1));
        if (count > 30) throw new WeatherRateLimitException();
        return provider.forecast(latitude, longitude);
    }
}
