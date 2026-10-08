package com.momentory.diary.application;

import org.springframework.stereotype.Service;
import com.momentory.common.time.DayBoundary;

@Service
public class DiaryWeatherService {
    private final DiaryQueryService query;
    private final DiaryService writer;
    private final WeatherProvider provider;

    public DiaryWeatherService(DiaryQueryService query, DiaryService writer, WeatherProvider provider) {
        this.query = query;
        this.writer = writer;
        this.provider = provider;
    }

    public DiaryView record(Long userId, Long id, double latitude, double longitude) {
        DiaryView diary = query.getOne(userId, id);
        if (diary.weather() != null) return diary;
        if (!DayBoundary.toLocalDate(diary.createdAt()).equals(DayBoundary.today())) {
            throw new DiaryWeatherDateException();
        }
        // 좌표는 DB에 저장하지 않는다. 네트워크 대기 동안 DB 트랜잭션도 잡지 않는다.
        String weather = provider.current(latitude, longitude);
        return writer.recordWeather(userId, id, weather);
    }
}
