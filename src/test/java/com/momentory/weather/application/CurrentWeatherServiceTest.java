package com.momentory.weather.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.momentory.weather.infrastructure.MetForecastClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

class CurrentWeatherServiceTest {
    @Test
    void limitsEachAuthenticatedUserToThirtyRequestsPerMinute() {
        var provider = Mockito.mock(MetForecastClient.class);
        var elapsedNanos = new AtomicLong();
        var service = new CurrentWeatherService(provider, true, elapsedNanos::get);
        service.current(1L, 37.57, 126.98);
        elapsedNanos.set(Duration.ofSeconds(30).toNanos());
        for (int i = 1; i < 30; i++) service.current(1L, 37.57, 126.98);
        elapsedNanos.set(Duration.ofSeconds(59).toNanos());
        assertThatThrownBy(() -> service.current(1L, 37.57, 126.98))
                .isInstanceOf(WeatherRateLimitException.class);
        service.current(2L, 37.57, 126.98);
        elapsedNanos.set(Duration.ofMinutes(1).toNanos() + 1);
        service.current(1L, 37.57, 126.98);
        verify(provider, times(32)).forecast(37.57, 126.98);
    }

    @Test
    void switchDisablesLookup() {
        var provider = Mockito.mock(MetForecastClient.class);
        var service = new CurrentWeatherService(provider, false);
        assertThatThrownBy(() -> service.current(1L, 37.57, 126.98))
                .isInstanceOf(WeatherDisabledException.class);
        Mockito.verifyNoInteractions(provider);
    }
}
