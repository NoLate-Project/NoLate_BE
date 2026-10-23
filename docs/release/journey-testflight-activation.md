# TestFlight 길안내 운영 서버 활성화

`main`의 `application-journey-testflight.yml`은 길안내 제품 기능을 켜는 명시적
프로필이다. Compose의 기본값은 계속 `prod`이므로 단순 재시작만으로 켜지지 않는다.
운영 배포 담당자가 새 서버 코드를 배포할 때 같은 셸에서 다음과 같이 실행한다.

```sh
SPRING_PROFILES_ACTIVE=prod,journey-testflight docker compose up -d --build app
```

기동 전에 운영 `env.properties` 또는 컨테이너 환경에 다음 값이 있는지 확인한다.
값이나 키 원문을 로그·채팅·저장소에 남기지 않는다.

- `routing.tmap.app-key`, 서울 버스 키(`transit.seoul.bus-api-key` 또는
  `transit.seoul.api-key`), `DATA_GO_KR_SUBWAY_PATH_API_KEY`, 서울 실시간 지하철 키
  (`transit.seoul.subway-api-key` 또는 `transit.seoul.api-key`)
- 기본 서울 버스 URL이 HTTP라면 `transit.seoul.allow-insecure-http=true`
- `journey.activity.encryption-key`: Base64로 인코딩한 32바이트 키
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
