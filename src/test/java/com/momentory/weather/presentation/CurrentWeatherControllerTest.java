package com.momentory.weather.presentation;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.momentory.auth.security.LoginAuthenticationToken;
import com.momentory.auth.security.LoginPrincipal;
import com.momentory.auth.security.SecurityConfiguration;
import com.momentory.user.domain.UserRole;
import com.momentory.weather.domain.CurrentWeather;
import com.momentory.weather.application.CurrentWeatherService;
import com.momentory.weather.domain.WeatherCondition;
import com.momentory.weather.application.WeatherDisabledException;
import com.momentory.weather.application.WeatherRateLimitException;
import com.momentory.weather.application.WeatherUpstreamException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(value = CurrentWeatherController.class, properties = {
        "JWT_SECRET=JZP9amP0y2bXk2LG9f9piS5jH3vK9B5w7qxgEriqMA4=",
        "JWT_REFRESH_EXPIRATION=30d"})
@Import({CurrentWeatherExceptionHandler.class, SecurityConfiguration.class})
@ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocConfigProperties.class,
        SpringDocWebMvcConfiguration.class})
class CurrentWeatherControllerTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean CurrentWeatherService service;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/weather/current").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":37.57,\"longitude\":126.98}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(service);
    }

    @Test
    void returnsWeatherWithoutCoordinates() throws Exception {
        var weather = new CurrentWeather(WeatherCondition.from("clearsky_day", 0.0),
                "clearsky_day", "맑음", 22, Instant.parse("2026-10-15T13:00:00Z"),
                Instant.parse("2026-10-15T12:31:07Z"));
        when(service.current(eq(42L), anyDouble(), anyDouble())).thenReturn(weather);
        mockMvc.perform(post("/api/v1/weather/current").with(authentication(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":37.57,\"longitude\":126.98,\"extra\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condition.code").value("clear.day"))
                .andExpect(jsonPath("$.temperatureC").value(22))
                .andExpect(jsonPath("$.updatedAt").value("2026-10-15T12:31:07Z"))
                .andExpect(jsonPath("$.latitude").doesNotExist());
    }

    @Test
    void rejectsInvalidCoordinatesWithoutEchoingThem() throws Exception {
        for (String body : new String[] {"{}", "{\"latitude\":91,\"longitude\":0}",
                "{\"latitude\":\"37.57\",\"longitude\":126.98}", ""}) {
            mockMvc.perform(post("/api/v1/weather/current").with(authentication(principal()))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_COORDINATE"))
                    .andExpect(jsonPath("$.message").value("좌표가 올바르지 않습니다."));
        }
        verifyNoInteractions(service);
    }

    @Test
    void mapsOperationalFailuresToDocumentedErrors() throws Exception {
        for (Object[] example : new Object[][] {
                {new WeatherRateLimitException(), 429, "TOO_MANY_REQUESTS"},
                {new WeatherUpstreamException(WeatherUpstreamException.Reason.FAILED), 502, "WEATHER_UPSTREAM_FAILED"},
                {new WeatherDisabledException(), 503, "WEATHER_UNAVAILABLE"},
                {new WeatherUpstreamException(WeatherUpstreamException.Reason.TIMEOUT), 504, "WEATHER_UPSTREAM_TIMEOUT"}
        }) {
            when(service.current(eq(42L), anyDouble(), anyDouble())).thenThrow((RuntimeException) example[0]);
            mockMvc.perform(post("/api/v1/weather/current").with(authentication(principal()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"latitude\":37.57,\"longitude\":126.98}"))
                    .andExpect(status().is((int) example[1]))
                    .andExpect(jsonPath("$.code").value(example[2]));
        }
    }

    private LoginAuthenticationToken principal() {
        return new LoginAuthenticationToken(new LoginPrincipal(42L, UserRole.USER), List.of());
    }

    @Test
    void documentsWeatherContract() throws Exception {
        var result = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var docs = objectMapper.readTree(result.getResponse().getContentAsString());
        var operation = docs.at("/paths/~1api~1v1~1weather~1current/post");
        org.assertj.core.api.Assertions.assertThat(operation.path("responses").path("200")
                .at("/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/CurrentWeatherResponse");
        var request = docs.at("/components/schemas/CurrentWeatherRequest/properties");
        org.assertj.core.api.Assertions.assertThat(request.size()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(request.path("latitude").path("type").asText()).isEqualTo("number");
        org.assertj.core.api.Assertions.assertThat(request.path("longitude").path("type").asText()).isEqualTo("number");
        var response = docs.at("/components/schemas/CurrentWeatherResponse/properties");
        org.assertj.core.api.Assertions.assertThat(response.size()).isEqualTo(9);
        org.assertj.core.api.Assertions.assertThat(response.has("latitude")).isFalse();
        org.assertj.core.api.Assertions.assertThat(response.has("updatedAt")).isTrue();
        for (Object[] error : new Object[][] {
                {"400", "INVALID_COORDINATE", "좌표가 올바르지 않습니다."},
                {"401", "AUTHENTICATION_REQUIRED", "인증이 필요합니다."},
                {"429", "TOO_MANY_REQUESTS", "요청이 너무 많습니다."},
                {"502", "WEATHER_UPSTREAM_FAILED", "날씨를 가져오지 못했어요."},
                {"503", "WEATHER_UNAVAILABLE", "날씨 기능을 사용할 수 없습니다."},
                {"504", "WEATHER_UPSTREAM_TIMEOUT", "날씨를 가져오지 못했어요."}
        }) {
            var documented = operation.path("responses").path((String) error[0]);
            org.assertj.core.api.Assertions.assertThat(documented.at("/content/application~1json/schema/$ref").asText())
                    .isEqualTo("#/components/schemas/ApiErrorResponse");
            var value = documented.at("/content/application~1json/examples/" + error[1] + "/value");
            org.assertj.core.api.Assertions.assertThat(value.path("code").asText()).isEqualTo(error[1]);
            org.assertj.core.api.Assertions.assertThat(value.path("message").asText()).isEqualTo(error[2]);
        }
    }
}
