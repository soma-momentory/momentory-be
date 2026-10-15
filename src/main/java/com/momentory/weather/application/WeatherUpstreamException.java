package com.momentory.weather.application;

public class WeatherUpstreamException extends RuntimeException {
    public enum Reason { FAILED, TIMEOUT }
    private final Reason reason;

    public WeatherUpstreamException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
