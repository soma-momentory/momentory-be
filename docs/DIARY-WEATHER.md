# 일기 날씨 기록

- PUT /api/v1/diaries/{id}/weather: 인증 사용자의 오늘 일기에 위치 기반 예보를 한 번 기록한다.
- 요청: latitude, longitude (숫자). 범위는 위도 ±90, 경도 ±180.
- 성공: 기존 DiaryResponse에 선택적인 weather 문자열 추가. 월별·전체·단건 조회에도 반환.
- 오류: 좌표 오류 400, 미인증 401, 다른 사용자 또는 없는 일기 404, 지난 날짜 409, 제공자 실패 503.
- V25는 nullable VARCHAR(80) 하나를 추가하며 기존 날씨는 null을 유지한다.
- KST 04:00 날짜 경계를 조회 전과 저장 시 재검사한다. 최초 값은 유지하고 동시 저장은 행 잠금으로 직렬화한다.
- 외부 조회 동안 DB 트랜잭션을 열지 않는다. 좌표는 DB나 로그에 저장하지 않고 0.01도 단위로 제공자에 전달한다.
- MET Norway Locationforecast의 해당 시각 예보·기온이다. 관측소 실측값이 아니다. 앱에 출처 링크를 표시한다.
- 기본 URL은 https://api.met.no 이며 API 키가 필요하지 않다. 테스트에서는 weather.base-url로 대체한다.
- User-Agent에 앱 웹사이트를 명시하고, 응답 Expires에 따라 최대 30분·512개 지역 결과를 메모리 캐시한다.
- 위치 권한을 포함해 네이티브 앱을 재빌드해야 한다. 서버 배포 전에는 새 API가 없어 조회가 실패한다.

공식 문서: https://api.met.no/ · https://api.met.no/doc/GettingStarted · https://api.met.no/doc/ForecastJSON
