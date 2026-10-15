# 홈 현재 날씨 API

- `POST /api/v1/weather/current`는 Bearer 인증을 요구한다. 좌표는 HTTPS 요청 본문에 숫자 `latitude`, `longitude`로만 보낸다. 응답에는 좌표가 없다. 조회는 일기와 DB를 변경하지 않는다.
- 범위는 위도 −90~90, 경도 −180~180이다. BE가 다시 소수 둘째 자리로 반올림하여 MET Norway Locationforecast 2.0 `compact`에 전달한다. 새 API는 기존 일기 날씨 API의 코드와 저장 흐름을 변경하지 않는다.
- 조건 코드는 `{family}[.{intensity}][.showers][.thunder][.{daylight}]` 순서다. 알 수 없는 MET 기호는 `unknown`으로 반환하여 FE가 그림을 숨길 수 있게 한다. 풍속이 10.8 m/s 이상이면 `windy`가 참이다.
- `forecastTime`은 가장 가까운 MET 예보 칸, `updatedAt`은 MET `properties.meta.updated_at`이다. 기온이 없거나 유효하지 않으면 `temperatureC`는 `null`이다.
- 홈 전용 서버 캐시는 0.01° 격자 좌표만 키로 사용하고, 최소 필드만 값으로 보관한다. 사용자 식별자와 원본 MET 응답은 저장하지 않는다. MET `Expires`와 60분 중 짧은 시간에 만료하며, `Expires`가 없으면 30분이다. 최대 512개 항목이며 Caffeine이 만료 항목을 제거한다. 별도 사용자별 요청 계수는 최대 1분 동안 보관하고 분당 30회를 제한한다.
- 만료 뒤 좌표 키를 남기지 않는 결정에 따라, 만료된 격자는 `If-Modified-Since` 재검증 대신 새 예보를 요청한다. 기존 일기 MET 클라이언트와 캐시는 그대로 유지되므로 홈 조회와 일기 기록이 같은 격자에서 별도로 MET를 호출할 수 있다.
- MET 연결 제한은 3초, 읽기 제한은 5초다. 서버 프로세스당 MET 요청을 초당 20회로 제한하며 MET 403/429 뒤에는 1분간 요청을 멈춘다. `weather.current.enabled=false`로 홈 조회를 끄면 `503 WEATHER_UNAVAILABLE`을 반환한다.
- 애플리케이션 코드는 좌표나 요청 본문을 로그에 쓰지 않는다. 배포 전 리버스 프록시와 APM의 요청 본문 캡처가 이 경로에서 꺼져 있는지 확인해야 한다. 외부 접속 URL의 TLS 종료 설정도 확인해야 한다.

MET 공식 문서: https://api.met.no/weatherapi/locationforecast/2.0/documentation · https://api.met.no/doc/TermsOfService
