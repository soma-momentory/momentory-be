package com.momentory.diary.application;

public interface WeatherProvider {
    String current(double latitude, double longitude);
}
