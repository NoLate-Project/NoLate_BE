# iOS 길안내 운영 서버 활성화 (TestFlight·App Store)

`main`의 `application-journey-testflight.yml`은 TestFlight와 App Store iOS 빌드가
공유하는 길안내 제품 기능 프로필이다. Compose의 기본값은 계속 `prod`이므로
단순 재시작만으로 켜지지 않는다.
운영 배포 담당자가 새 서버 코드를 배포할 때 같은 셸에서 다음과 같이 실행한다.

```sh
SPRING_PROFILES_ACTIVE=prod,journey-testflight docker compose up -d --build app
```

기동 전에 운영 `env.properties` 또는 컨테이너 환경에 다음 값이 있는지 확인한다.
값이나 키 원문을 로그·채팅·저장소에 남기지 않는다.

- `routing.tmap.app-key`, 서울 버스 키(`transit.seoul.bus-api-key` 또는
  `transit.seoul.api-key`), `DATA_GO_KR_SUBWAY_PATH_API_KEY`, 서울 실시간 지하철 키
  (`transit.seoul.subway-api-key` 또는 `transit.seoul.api-key`)
- `ODSAY_SERVER_API_KEY`: 저장한 ODsay 대중교통 경로의 ETA 재조회에 사용한다.
  이 프로필은 ODsay ETA를 켜고 TMAP 대중교통 ETA를 끈다. TMAP 지도·보행 키는
  그대로 필요하며, 서버에서 `TMAP_ETA_ENABLED=true`만 설정해도 이 프로필의
  ODsay 선택을 덮어쓰지 않는다.
- 기본 서울 버스 URL이 HTTP라면 `transit.seoul.allow-insecure-http=true`
- `JOURNEY_ACTIVITY_ENCRYPTION_KEY`: Base64로 인코딩한 32바이트 키. 배포 서버가
  실제로 마운트하는 `env.properties`에 한 번 설정하고 재시작 후에도 같은 값을 보존한다.
  로컬 개발용 `env.local.properties`의 값은 다른 서버로 자동 전달되지 않는다.
- Firebase와 Spring 스케줄링 활성화

누락되면 `JourneyOperationalReadinessGuard`가 기동을 차단한다. 기동 후 실제
컨테이너에서 `SPRING_PROFILES_ACTIVE=prod,journey-testflight`가 적용됐고 health가
정상인지 확인한 뒤, 시험 계정의 저장된 ODsay 경로로 기기 현장 검증을 진행한다.
단순 프로세스 재시작이나 앱 IPA 업로드만으로 서버 기능이 켜지지는 않는다.

문제가 생기면 이전 서버 이미지·코드로 되돌리고 프로필도 `prod`만 사용한다.
프로필만 해제할 때는 다음처럼 앱 컨테이너를 다시 생성한다.

```sh
SPRING_PROFILES_ACTIVE=prod docker compose up -d --no-deps --force-recreate app
```
