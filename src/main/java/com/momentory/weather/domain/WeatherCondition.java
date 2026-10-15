package com.momentory.weather.domain;

/** MET 기호를 클라이언트에서 확장 가능하게 해석할 수 있는 조건으로 변환한다. */
public record WeatherCondition(String code, String family, String intensity, boolean showers,
        boolean thunder, String daylight, boolean windy) {

    public static WeatherCondition from(String symbolCode, Double windSpeed) {
        String daylight = null;
        String base = symbolCode;
        for (String suffix : new String[] {"_day", "_night", "_polartwilight"}) {
            if (symbolCode.endsWith(suffix)) {
                daylight = suffix.equals("_night") ? "night" : "day";
                base = symbolCode.substring(0, symbolCode.length() - suffix.length());
                break;
            }
        }

        String family;
        String intensity = null;
        boolean showers = false;
        boolean thunder = false;
        switch (base) {
            case "clearsky" -> family = "clear";
            case "fair", "cloudy", "fog" -> family = base;
            case "partlycloudy" -> family = "partly_cloudy";
            default -> {
                String remainder = base;
                if (remainder.startsWith("light")) {
                    intensity = "light";
                    remainder = remainder.substring(5);
                    // MET의 두 공식 기호에는 light 뒤에 s가 하나 더 있다.
                    if (remainder.startsWith("ssleet") || remainder.startsWith("ssnow"))
                        remainder = remainder.substring(1);
                } else if (remainder.startsWith("heavy")) {
                    intensity = "heavy";
                    remainder = remainder.substring(5);
                }
                if (remainder.endsWith("andthunder")) {
                    thunder = true;
                    remainder = remainder.substring(0, remainder.length() - 10);
                }
                if (remainder.endsWith("showers")) {
                    showers = true;
                    remainder = remainder.substring(0, remainder.length() - 7);
                }
                family = switch (remainder) {
                    case "rain", "sleet", "snow" -> remainder;
                    default -> "unknown";
                };
                if (family.equals("unknown")) {
                    intensity = null;
                    showers = false;
                    thunder = false;
                }
            }
        }
        // 구름과 맑음에만 낮·밤 변형이 있다. 강수 소나기에는 변형이 있다.
        boolean usesDaylight = family.equals("clear") || family.equals("fair")
                || family.equals("partly_cloudy") || showers;
        if (!usesDaylight) daylight = null;
        StringBuilder code = new StringBuilder(family);
        if (intensity != null) code.append('.').append(intensity);
        if (showers) code.append(".showers");
        if (thunder) code.append(".thunder");
        if (daylight != null) code.append('.').append(daylight);
        boolean windy = windSpeed != null && Double.isFinite(windSpeed) && windSpeed >= 10.8;
        return new WeatherCondition(code.toString(), family, intensity, showers, thunder, daylight, windy);
    }

    public String label() {
        String name = switch (family) {
            case "clear" -> "맑음";
            case "fair" -> "대체로 맑음";
            case "partly_cloudy" -> "구름 조금";
            case "cloudy" -> "흐림";
            case "fog" -> "안개";
            case "rain" -> "비";
            case "sleet" -> "진눈깨비";
            case "snow" -> "눈";
            default -> "알 수 없는 날씨";
        };
        if (!family.equals("rain") && !family.equals("sleet") && !family.equals("snow")) return name;
        if (showers) name = name.equals("비") ? "소나기" : name + " 소나기";
        if (intensity != null) name = (intensity.equals("heavy") ? "강한 " : "약한 ") + name;
        if (thunder) name += " · 뇌우";
        return name;
    }
}
