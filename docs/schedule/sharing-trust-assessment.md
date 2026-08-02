# 일정 공유 운영 신뢰도 평가

평가일: 2026-08-01  
평가 기준: 코드 존재 여부가 아니라 운영 배포, 권한 오용, 장애 대응, MySQL 동시성, 실사용 링크까지 포함

## 결론

현재 저장소 구현과 자동화 검증의 신뢰도는 **94/100**, 아직 배포하지 않은 상태를 실제 운영으로
가정한 준비도는 **89/100**이다. 운영자 신고 대시보드, 초대 중복 수락 멱등성, 공유 생성 제한,
HTTPS 앱 링크 설정과 CI MySQL gate까지 구현되었다. 다만 운영 배포·서명 실기기 검증과 전용
지표/장애 알람은 저장소 내부 검증으로 대체할 수 없으므로 운영 점수에서 감점한다.

| 평가 영역 | 배점 | 운영 점수 | 근거 |
|---|---:|---:|---|
| 공유 기능·권한 경계 | 25 | 25 | 일정·카테고리·캘린더 공유/초대/회수, 중앙 접근 정책, 차단 관계의 조회·변경·알림·초대 수락 거부 |
| 신고·차단·운영 처리 | 20 | 19 | 6개 사유, 설명·내역, 중복/남용 방어, 양방향 차단, 운영자 allowlist 대시보드와 처리 감사 필드 |
| 데이터·동시성·멱등성 | 20 | 20 | 회원 row 선잠금, MySQL 경합 검증, 회원별 초대 수락 ledger, 중복 수락 시 동일 grant 반환·횟수 불변 |
| 앱 UX·HTTPS 연결 | 15 | 13 | 신고 모달·내역 화면, iOS/Android 앱 링크 설정과 서버 association 제공. 스토어 서명 실기기 미검증 |
| 배포·관측·장애 대응 | 20 | 12 | 전체 회귀, Docker E2E, CI 필수 gate, fail-closed 구성. 운영 migration·전용 지표/알람·복구 훈련 미실시 |
| **합계** | **100** | **89** |  |

## 구현된 운영자 대시보드

- 경로: `/sharing-admin`
- 일반 로그인 후 `SHARING_MODERATOR_MEMBER_IDS` allowlist를 다시 검사한다. 빈 allowlist는 모든
  운영 API를 거부한다.
- 접수·검토 중·처리 완료·기각 건수와 오래된 순 최대 100건을 상태별로 조회한다.
- 신고자/대상, 리소스, 사유, 설명, 처리자, 처리 메모를 확인하고
  `SUBMITTED → REVIEWING → RESOLVED/DISMISSED`로 처리한다.
- 종료된 신고는 다시 변경할 수 없고, 접수 상태로 되돌릴 수 없다.
- 정적 화면은 공개되어도 신고 데이터는 노출되지 않는다. 데이터 API는 JWT와 운영자 allowlist를
  모두 통과해야 하며 미인증 요청은 401, 일반 회원은 403으로 거부된다.

대시보드는 담당자가 정기적으로 확인하는 현재 운영 규모에는 충분하다. 다만 대시보드는 장애나
장기 미처리를 스스로 알려 주지 않으므로 24시간 대응 SLA가 생기면 `미처리 신고 최장 대기시간`,
`신고 처리 API 5xx`, `공유/초대 5xx 급증` 세 가지 운영자 알람을 추가한다. 사용자에게 신고·차단
사실을 푸시하는 알림은 보복과 개인정보 노출 위험 때문에 제공하지 않는다.

## 안전성과 오남용 방어

- 받은 활성 공유만 신고할 수 있고 동일 관계의 미처리 신고는 기존 신고를 반환한다.
- 신고는 rolling 24시간 20건, 직접 공유는 시간당 30건, 초대 생성은 시간당 10건으로 제한한다.
- 공유 제한은 Redis Lua의 원자적 증가/만료로 집행한다. Redis 장애 시 공유 mutation은 503으로
  fail-closed하며 제한 초과는 429를 반환한다.
- 어느 사용자가 먼저 차단했는지와 무관하게 기존 조회, 신규 공유, 초대 수락, 공유 캘린더 멤버,
  경로 참여자와 푸시 수신자를 동일하게 차단한다.
- 링크 수락은 `(invitation_id, member_id)` 고유 ledger로 기록한다. 같은 사용자의 재시도는 초대
  사용 횟수를 다시 올리거나 grant를 재생성하지 않는다.

## HTTPS 앱 링크

- 앱의 운영 공유 URL은 `https://nolate.jinuk.dev/share/{token}`이다.
- iOS Associated Domains와 Android `autoVerify` intent filter를 네이티브 설정에도 반영했다.
- 서버는 Apple AASA를 제공하며 `/share/*`와 현재 Team ID·Bundle ID를 반환한다.
- Android assetlinks는 `APP_LINKS_ANDROID_CERT_SHA256_FINGERPRINTS`가 없으면 403으로 fail-closed한다.
  실제 Play/App Signing SHA-256 지문을 운영 환경에 넣기 전에는 Android 링크를 출시 완료로 보지 않는다.

## 실행한 검증

- 백엔드 전체: **1,317 tests, failures 0, errors 0, skipped 0**
- 프론트 전체: **174 suites, 1,304 tests, failures 0**
- 프론트 TypeScript 검사와 네이티브/앱 링크 release configuration 검사 통과
- Docker MySQL 8.4 공유 E2E: **4 tests, skipped 0**
  - 캘린더 멤버 동시 추가의 단일 row 수렴
  - 초대 수락과 캘린더 보관 경합
  - 푸시 scanner 단일 생성자 선출
  - 공유 수락→동일 초대 재수락 멱등성→신고→차단→거부→차단 해제 복구
- Playwright + Docker 운영 대시보드 E2E
  - 운영자 로그인, 접수→검토 중→처리 완료, 처리자·메모·완료 시각의 MySQL 반영 확인
  - 일반 회원 403, 미인증 API 401, 브라우저 콘솔 오류 0 확인
- `2026-08-01-sharing-safety-v1` migration
  - marker 1개, 테이블 3개, 운영 처리 컬럼 3개, 초대 수락 복합 unique key 확인
  - 같은 migration 재적용 차단 확인
- CI는 Docker를 건너뛸 수 없는 MySQL 공유 E2E를 일반 테스트보다 먼저 실행한다.

## 운영에서 90점 이상을 확정하는 최소 조건

1. 운영 MySQL 백업 후 migration을 적용하고 postcondition·marker를 확인한다.
2. `SHARING_MODERATOR_MEMBER_IDS`와 Play/App Signing SHA-256 지문을 운영 secret으로 설정한다.
3. `https://nolate.jinuk.dev/.well-known/*`를 외부 HTTPS에서 확인하고 iOS/Android 스토어 서명
   실기기에서 공유 링크 수락→차단→접근 거부를 실행한다.
4. 알람을 계속 제외한다면 운영자가 대시보드를 확인할 주기와 미처리 신고 SLA를 운영 절차에 명시한다.

위 네 조건 중 1~3이 완료되면 현재 구현 기준 운영 신뢰도는 **93/100**으로 재평가할 수 있다.
전용 알람 없이도 작은 규모 운영은 가능하지만, 대시보드 확인이 사람의 기억에만 의존한다는 위험은
그대로 남는다.
