# App Store & Google Play Release Roadmap

Last reviewed: 2026-07-26 KST

## 현재 판정

> **출시 준비 완료 아님.**

ETA·푸시와 일정 공유 fail-closed에 더해 Apple 탈퇴 revoke, 앱 밖 계정 삭제,
liveness/readiness, BE metric·alert rule, FE 공급망 소스가 통합됐다. A–D 회귀와
FE 자동검증, iOS Simulator·Android API 35의 로컬 Release 실행도 통과했다. 현재
exact는 BE `d3a9c038`, FE `77ca5e4`이며 A–D는 모두 skip·fail·error 0, 최종
독립감사는 P0/P1 0(P2 테스트 견고성 3)이다. BE exact는 host 전체 160 suites /
941 tests와 Docker 무캐시 build, MySQL 8.4 fresh create→same-DB validate 및 HTTP
계약까지 통과했다. 영구 앱 ID와 배포 서명, Apple 실제 계정·provider 증거, 계정
삭제 canonical domain·SMTP·정책 승인, 실제 orchestrator의 장애·복구 probe,
dashboard·on-call·FE crash SDK, store-signed 실기기·실제 provider 검증이 남아
있어 production 제출 단계가 아니다.

## 상태 기준

- `완료`: 해당 범위가 통합됐고 독립 소스 검토와 전체 자동 테스트까지 통과했다.
- `부분 완료`: 코드와 자동 테스트는 있으나 실기기, 스테이징, 운영 또는 외부 콘솔 증거가 남았다.
- `미완료`: 실기기, 운영, 서명, 스토어 콘솔 등의 실행·검증 증거가 없다.
- `개발 필요`: FE, BE, DB, Web 또는 네이티브 소스 구현이 추가로 필요하다.

복합 작업은 가장 낮은 상태로 표시한다. 표는 중요도 순이며 `완료` 항목은 가장 아래에 둔다.

## 출시 준비 상태표

| 우선순위 | 작업 | App Store | Play Store | 소스 영향 | 상태 | 완료 조건 |
| --- | --- | --- | --- | --- | --- | --- |
| P0-01 | 영구 Bundle ID / Package Name과 provider 매핑 | 필수 | 필수 | FE 네이티브·빌드, 외부 SDK | 개발 필요 | FE의 `com.anonymous.*`, App Group·Keychain·Firebase client 값을 영구 release identity로 바꾸고 Apple·Google·Kakao·Naver·TMAP/ODsay 콘솔을 같은 ID에 재연결 |
| P0-02 | Sign in with Apple 탈퇴 token revoke | 필수 | 해당 없음 | FE 인증, BE Apple 연동·보안 | 부분 완료 | BE `48d6915`의 durable exchange·암호화 보관·revoke·재시도·fail-closed가 exact `d3a9c038`에 포함됐고 FE 수동 조치 UX도 exact `77ca5e4`에 병합됨. 운영 Apple credentials와 실제 계정 증거는 남음 |
| P0-03 | 앱 밖 계정 삭제 요청 URL | 선택 | 필수 | Web, BE API·보안 | 부분 완료 | `caa9ba2`의 공개 페이지, `COMMON` email 본인 확인, rate limit, single-use grant, cleanup과 기본 disabled/fail-closed가 BE exact `d3a9c038`에 포함됨. canonical public domain·SMTP·정책 승인은 남음 |
| P0-04 | 일정 공유 UGC 안전장치 또는 기능 비활성화 | 필수 | 필수 | FE, BE, DB, 운영 | 부분 완료 | FE `adfd489a`, BE `e9e753c6`의 전역 off와 dormant 보존이 승인됨. 로컬 iOS Release Simulator·Android API 35 debug-key signed Release APK·통합 BE에서 공유 UI/deep link가 닫히고 인증 API 8종이 `403 C006`, provider 호출 0임을 확인. store-signed·prod 증거는 남음 |
| P0-05 | 운영 DB migration 체계 | 공통 운영 | 공통 운영 | BE, DB, CI/CD | 부분 완료 | 버전 SQL, production schema guard와 rollout runbook을 실제 MySQL 8에 적용하고 marker, roll-forward/rollback, backup/restore를 스테이징에서 검증 |
| P0-06 | Distribution Archive와 서명 AAB | 필수 | 필수 | FE 네이티브, CI, secret 운영 | 미완료 | iOS Simulator Release와 Android debug-key signed Release APK는 실행됨. 영구 ID의 iOS Distribution Archive와 Android release AAB를 생성·설치하고 스토어 사전 검사 통과 |
| P0-07 | 실기기 ETA·알림 acceptance | 필수 | 필수 | 실패 시 FE/BE 수정 | 미완료 | iOS Simulator Release의 로그인 렌더·PID 생존·crash 0과 Android API 35 Release APK의 cold 로그인 렌더·안정성은 통과함. iPhone과 Android 12/13+ 물리 기기에서 실제 TMAP·FCM·APNs 상태·권한·액션 매트릭스 통과 |
| P0-08 | MySQL 8 다중 인스턴스·장애 복구 | 공통 운영 | 공통 운영 | BE 운영·DB | 부분 완료 | exact `d3a9c038`에서 MySQL 8.4 explicit 3 suites / 5 tests가 skip·failure·error 0이고 Apple `VARCHAR(16384)` metadata는 `ascii`/`ascii_bin`/`16384`로 확인됨. exact Docker image도 fresh create 31 tables→same-DB validate로 재기동됨. 운영 동등 다중 인스턴스의 scheduler·lease·crash/restart 증거는 남음 |
| P0-09 | Firebase·Apple·Google·Kakao·Naver·TMAP 운영 설정 | 필수 | 필수 | 주로 외부 콘솔, 일부 FE 설정 | 미완료 | release ID·SHA·인증서·redirect·API 제한·APNs key로 실제 로그인·지도·푸시 성공 및 Google Calendar OAuth 공개 앱 검증 |
| P0-10 | 심사 계정과 reviewer 경로 | 필수 | 필수 | 운영 데이터, 리뷰 노트 | 미완료 | 만료되지 않는 계정, 샘플 일정, 공유-off를 포함한 비자명 기능 설명과 심사 기간 BE 가용성 준비 |
| P0-11 | 스토어 메타데이터와 에셋 | 필수 | 필수 | 에셋, 콘솔 | 미완료 | 지원·개인정보 URL, 설명, 연령 등급, iPhone 스크린샷, Play 아이콘·feature graphic·스크린샷 준비 |
| P0-12 | Play production access | 해당 없음 | 계정 조건부 필수 | Play Console | 미완료 | 2023-11-13 이후 생성 개인 계정이면 12명이 연속 14일 opted-in한 closed test 후 production access 신청 |
| P0-13 | 개인정보·약관·App Privacy·Data safety 정합성 | 필수 | 필수 | BE 법률 문서, FE fallback, 콘솔 | 부분 완료 | 위치·일정·검색·푸시 토큰·빠른 입력·Firebase/Groq 등 실제 SDK 흐름과 연령 정책을 단일 데이터 맵으로 대조하고 게시 |
| P0-14 | ETA 출처·신선도·동일 경로 비교 | 공통 | 공통 | FE, BE, DB | 부분 완료 | 통합 코드는 승인됨. 실제 TMAP에서 live 증가·감소·timeout·fallback·stale·경로 변경을 운영 DB/UI까지 검증 |
| P0-15 | 푸시 내구성·권한 fence·다기기 재시도 | 공통 | 공통 | FE, BE, DB | 부분 완료 | 통합 코드는 승인됨. 실제 FCM/APNs와 다중 인스턴스에서 중복·유실·계정 전환·부분 실패를 계측 |
| P0-16 | FE auth epoch·로컬 purge·신뢰도 UI | 필수 | 필수 | FE JS·native storage | 부분 완료 | 자동 테스트와 Simulator/AVD 더미 인증 cleanup은 확인됨. 서명 실기기에서 로그아웃·탈퇴·재로그인·계정 전환·강제 종료·오프라인 복구 확인 |
| P0-17 | 배포 health check 계약 | 공통 운영 | 공통 운영 | BE, 배포 | 부분 완료 | 인증 없는 liveness/readiness `ad42618`, deploy probe `58a2b43`, GET-only 보호 `b0d6c52`가 통합됨. exact Docker에서 3경로 `200`·`no-store`·`UP`, HEAD/POST/PUT/PATCH/DELETE/OPTIONS `401` 확인. 실제 orchestrator의 장애·복구 증거는 남음 |
| P1-01 | 버전·빌드 번호와 release config | 필수 | 필수 | FE app config, Xcode, Gradle, CI | 부분 완료 | 영구 ID 전환 후 앱·확장·Android 버전 정책을 CI에서 검사하고 실제 업로드로 확인 |
| P1-02 | 푸시·ETA 운영 관측과 호출 경보 | 권장 | 권장 | FE/BE metric·crash SDK, 운영 | 부분 완료 | `187619c`에 Micrometer/Prometheus metrics·alert rules와 promtool CI 검증이 통합됨. 실제 수집·dashboard·on-call routing·alert firing과 FE crash SDK는 남음 |
| P1-03 | HTTPS Universal Link / App Link | 권장 | 권장 | FE 네이티브, Web | 개발 필요 | AASA/assetlinks와 HTTPS 초대 링크를 제공하고 설치·미설치 fallback 검증 |
| P1-04 | Android adaptive icon | 해당 없음 | 권장 | FE Android resource/config | 부분 완료 | 원형·사각형 launcher와 Play listing에서 잘림 없는지 release 빌드로 확인 |
| P1-05 | FE build/CLI 공급망과 간접 lodash | 권장 | 권장 | FE lock, CI Node/native build | 부분 완료 | 공급망·toolchain `d997ad4` 감사 critical 0, high 1은 production bundle에 포함되지 않는 legacy build-tool chain에만 남음. FE 178 suites / 1,392 tests, lint 오류 0·경고 166과 로컬 Release 통과. Distribution Archive·서명 AAB·스토어 검증은 남음 |
| P2-01 | 반복 일정 | 제품 선택 | 제품 선택 | FE, BE, DB | 개발 필요 | 초기 출시 차단 항목은 아님. UT에서 수요 확인 후 발생·수정·push job 정책 구현 |
| P2-02 | 다중 시간대·DST | 제품 선택 | 제품 선택 | FE, BE, DB | 개발 필요 | 국내 MVP 비차단. 해외 확장 전 사용자 시간대·DST·종일 일정 규칙 구현 |

## 완료된 소스 검증

| 우선순위 | 작업 | 상태 | 증거 |
| --- | --- | --- | --- |
| 완료-01 | FE 신뢰성 통합 | 완료 | `680345135284d68b144091a31ab4df27a557dee2`: release config·typecheck 통과, lint 오류 0, 173 suites / 1,292 tests 통과 |
| 완료-02 | FE runtime HTTP dependency P0 | 완료 | Axios 1.18.1·form-data 4.0.6, direct runtime critical/high 0, 최소 lock diff 독립 감사 P0 0 / P1 0 |
| 완료-03 | BE ETA·푸시 통합 | 완료 | `3986d84552a162281986432d62295bc404153b09`: 768 tests 중 765 실행 통과, MySQL Docker 3건 조건부 스킵, 실패 0 |
| 완료-04 | 최종 exact 독립 재감사 | 완료 | BE `d3a9c038`·FE `77ca5e4` 최종 감사에서 P0 0 / P1 0. 비차단 P2는 MySQL live DDL assertion, Docker availability 예외 범위, ENTRYPOINT semantic test 강화 3건 |
| 완료-05 | 원본 변경 보호 | 완료 | 기존 dirty FE/BE 작업 트리를 유지하고 별도 integration worktree에서 통합 |
| 완료-06 | FE 일정 공유 production-off | 완료 | 공유-off `adfd489a52175d4fe4301f26dffec1746ba7a991`가 최종 FE HEAD `77ca5e4`에 포함됨. 현행 FE verify 178 suites / 1,392 tests, lint 오류 0·경고 166 |
| 완료-07 | BE 현행 exact 소스 게이트 | 완료 | BE exact `d3a9c038`: host 전체 160 suites / 941 tests, 4 skipped, failure·error 0. 실제 Apple MySQL test는 실행됐고 skip 0 |
| 완료-08 | exact A–D 회귀 | 완료 | BE `d3a9c038`: A ETA 8 suites/59, B push 9/99. FE `77ca5e4`: C detail/notification 6/60, D next-departure 3/71. 모두 skip·fail·error 0 |
| 완료-09 | 공유-off 로컬 실제 런타임 | 완료 | iOS Release Simulator·Android API 35 debug-key signed Release APK의 공유 UI 0·공유 deep link 4종 home fail-closed, 통합 BE 인증 공유 API 8종 `403 C006`, 외부 provider 호출 0 |
| 완료-10 | 로컬 앱 안정성·번들 독립성 | 완료 | iOS Release embedded Hermes·로그인 렌더·PID 생존·crash 0. Android Release APK는 embedded Hermes, cold 1.201초 로그인 렌더·`RESUMED`, ANR·fatal·crash·ReactNativeJS E/F 0 |
| 완료-11 | Apple 탈퇴 revoke 소스 게이트 | 완료 | BE `48d6915`의 durable exchange·암호화 보관·revoke·재시도·fail-closed가 exact `d3a9c038`에 포함됨. FE 수동 조치 UX는 exact `77ca5e4` |
| 완료-12 | 앱 밖 계정 삭제 소스 게이트 | 완료 | `caa9ba2`가 BE exact `d3a9c038`에 포함됨: 공개 페이지, `COMMON` email verification, rate limit, single-use grant, cleanup과 기본 disabled/fail-closed |
| 완료-13 | health·배포 probe 소스·로컬 런타임 게이트 | 완료 | liveness/readiness `ad42618`, deploy probe `58a2b43`, GET-only 보호 `b0d6c52` 통합. exact Docker의 health 3종 GET `200`; 비-GET·OPTIONS 보호 및 actuator 비공개 확인 |
| 완료-14 | BE observability 소스 게이트 | 완료 | `187619c`: Micrometer/Prometheus metrics·rules와 promtool CI 검증 통합 |
| 완료-15 | FE 공급망·로컬 Release 소스 게이트 | 완료 | 공급망/toolchain `d997ad4` audit critical 0, legacy build-tool high 1은 production bundle 비포함. iOS 안정화 `0417ae0`, Android TMAP Release fix·최종 FE HEAD `77ca5e4`는 origin push 완료 |
| 완료-16 | MySQL 8.4 explicit 게이트 | 완료 | exact `d3a9c038`, 3 suites / 5 tests: 계정 삭제 1, Apple migration 1, 기존 shared-calendar concurrency 3. skipped·failure·error 0; Apple metadata `ascii`/`ascii_bin`/`16384` |
| 완료-17 | BE Docker build·MySQL 런타임 게이트 | 완료 | exact `d3a9c038` 무캐시 build 내부 941 tests / 5 skipped, failure·error 0. `NoLateApplicationKt`, UID 10001로 MySQL 8.4 fresh create→same-DB validate 후 healthy·restart 0 |

위 `완료`는 명시된 소스·자동·로컬 실행 범위다. iOS Simulator Release는
Distribution Archive가 아니고 Android debug-key signed Release APK는 Play
App Signing을 거친 AAB가 아니며, H2/Testcontainers는 스테이징·운영을 대체하지 않는다.

## 2026-07-26 로컬 실제 실행 증거

- iOS: iOS 26.1 Simulator용 Release를 production sharing-off로 빌드했다. embedded
  Hermes bundle로 Metro 없이 로그인 화면이 렌더됐고 PID 생존과 crash 0을 확인했다.
- Android: API 35 emulator에서 1,019 tasks로 만든 debug-key signed Release APK의
  v2 signature와 embedded Hermes bundle을 확인했다. cold 1.201초에 로그인 화면이
  렌더되고 activity가 `RESUMED`였으며 ANR·fatal·crash·ReactNativeJS E/F는 0이었다.
- BE: 실제 `NoLateApplicationKt`를 H2/전용 Redis로 기동해 sharing `DISABLED`,
  인증 공유 API 8종 `403 C006`, 외부 provider/TCP 호출 0을 확인했다. 계정 삭제
  소스를 포함한 exact `d3a9c038`의 host 전체 결과는 160 suites / 941 tests,
  4 skipped, failure·error 0이며 실제 Apple MySQL test는 skip 0이다.
- MySQL: exact `d3a9c038`을 MySQL 8.4에서 explicit 3 suites / 5 tests(계정 삭제 1,
  Apple migration 1, 기존 shared-calendar concurrency 3)로 실행해 skipped·failure·
  error 0을 확인했다. Apple `VARCHAR(16384)` actual metadata가
  `ascii`/`ascii_bin`/`16384`임도 확인했다.
- Docker: Git archive exact `d3a9c038` 무캐시 build 내부 941 tests / 5 skipped,
  failure·error 0이다. fresh MySQL 8.4에서 `ddl-auto=create`로 31 tables와 Apple
  ASCII column을 만든 뒤 같은 DB에 `ddl-auto=validate`로 재기동해 schema error 0,
  healthy·restart 0을 확인했다. create 전 존재하지 않는 FK를 내리는 Hibernate
  1146 log 2건은 있었지만 vendor 1074·Apple table 누락은 없었다.
- HTTP: `/health`, `/health/liveness`, `/health/readiness`는 모두 `200`,
  `Cache-Control: no-store`, 정확한 `{"status":"UP"}`였다. 세 경로의
  HEAD/POST/PUT/PATCH/DELETE/OPTIONS는 `401`(TRACE `400`), actuator root·health·
  prometheus와 `/prometheus`는 `401`이었다. 계정 삭제 페이지 GET은 `200`, disabled
  same-origin POST는 정보·canary를 노출하지 않고 `503`으로 fail-closed했다.
- 한계: 앱 결과는 Simulator/debug-key signed APK이며 store-signed 물리 기기
  검증이 아니다. Docker의 local profile `create→validate`는 reviewed production
  migration·marker·backup/restore, 다중 인스턴스·장애 복구, 실제 orchestrator,
  dashboard·on-call과 provider 검증을 대체하지 않는다.

## 실제 소스에 영향을 주는 묶음

### 1. 앱 ID·서명·provider

| 변경 | FE | BE | DB / Web |
| --- | --- | --- | --- |
| 영구 앱 ID | app config, Android namespace/applicationId, Xcode targets, App Group, Keychain, Firebase 파일 | 허용 redirect/client 검증이 있으면 함께 변경 | provider 콘솔과 association 파일 |
| Apple revoke | authorization code 전달과 수동 조치 UX, exact `77ca5e4` | durable exchange·암호화 보관·revoke·재시도·fail-closed `48d6915`, exact `d3a9c038` | MySQL 8.4 metadata `ascii`/`ascii_bin`/`16384`; 운영 Apple credentials·실제 계정 증거 남음 |
| FE 공급망·Release | toolchain `d997ad4`, iOS 안정화 `0417ae0`, Android TMAP Release fix·최종 HEAD `77ca5e4` | 없음 | Distribution signing·스토어 CI |
| release signing | Xcode/Gradle/CI secret 참조 | 없음 | App Store Connect / Play Console |

### 2. 계정·UGC·법률

| 변경 | FE | BE | DB / Web |
| --- | --- | --- | --- |
| Play 외부 탈퇴 | 설정의 canonical public URL·정책 문구 확인 | `COMMON` email verification, rate limit, single-use grant, cleanup `caa9ba2` | 공개 페이지 통합, canonical domain·SMTP·정책 승인 남음 |
| 신고·차단 | 일정/사용자 신고, 차단·해제 UI | 신고 처리, 공유·초대·기존 접근 집행 | 신고·차단 schema와 운영 조회 |
| 공유 비활성화 | exact `77ca5e4`에서 release flag false·UI/deep link fail-closed | exact `d3a9c038`에서 `DISABLED`·공유 API `403 C006` | store-signed·production에서 기존 row·queued delivery 비노출 증거 남음 |
| 개인정보 정합성 | 앱 내 fallback 문서, 권한 문구 | 법률 문서와 보유·삭제 정책 | 두 스토어 privacy 선언 |

### 3. ETA·알림 운영 검증

| 변경 | FE | BE | DB / 운영 |
| --- | --- | --- | --- |
| provenance UI | live/fallback, stale, 갱신 시각, confidence | source/fetchedAt/failure/fingerprint 계산 | job 상태 보관 |
| delivery reliability | 권한·토큰 복구, 탭·액션, 계정 전환 | outbox, delivery, history, dispatch fence, partial retry | lease·상태·지표 |
| health·deploy probe | 없음 | liveness/readiness `ad42618`, GET-only 보호 `b0d6c52`; exact Docker GET 3종 `200` | deploy probe `58a2b43`; 실제 orchestrator 장애·복구 증거 남음 |
| 장애 대응 | FE crash SDK 남음 | Micrometer/Prometheus metrics `187619c` | rules·promtool CI 통합, dashboard·on-call·실제 alert 증거 남음 |
| BE build/runtime | 없음 | exact `d3a9c038` 무캐시 build, host 160 suites / 941 tests, Docker 내부 941 tests | MySQL 8.4 fresh create→same-DB validate·HTTP 계약 통과; production migration·다중 인스턴스·장애 복구 남음 |

## 알림 전달 보장 경계

- 같은 logical event의 확인된 성공 기기에는 재전송하지 않는다.
- 다기기 일부 실패는 실패 기기만 같은 generation/check event로 제한 재시도한다.
- provider 호출 결과가 불확실한 `DISPATCHING` 구간은 자동 재시도하지 않는 **at-most-once 경계**다.
- 따라서 “exactly once”나 “유실 0”을 주장하지 않는다. 중복 방지를 우선한 경계이며 실제 provider 계측과 고객 대응 절차가 필요하다.

## 실기기·스테이징 게이트

| 대상 | 반드시 통과할 항목 |
| --- | --- |
| iPhone | foreground/background/terminated, 탭, `지금 출발`, tray/badge, 권한 복구, 서명 App Group/Keychain extension |
| Android 12 | foreground/background/terminated, 채널, tray, 탭·액션, 로그아웃 후 이전 계정 오발송 없음 |
| Android 13+ | 런타임 알림 권한 거부·복구와 위 전체 시나리오 |
| TMAP | 동일 경로 live 증가·감소, timeout, fallback, stale, 경로 변경 시 비교 억제 |
| FCM/APNs | 다계정·다기기, invalid token, 일부 실패, timeout/불확실 응답, 앱 상태별 수신 |
| MySQL 8 | 명시적 migration, 두 scheduler 경쟁, lock/deadlock, lease heartbeat/만료, crash/restart |

## 제출 순서

1. 영구 앱 ID 확정 및 모든 provider 재매핑
2. Apple 실제 계정·credentials로 revoke를 검증하고 외부 탈퇴 페이지를 canonical
   domain·SMTP에 연결해 정책 승인을 완료
3. 공유 비활성화의 서명·운영 증거 확보
4. liveness/readiness probe를 실제 orchestrator에 적용하고 Prometheus
   dashboard·on-call·FE crash SDK 연결
5. DB migration 체계와 MySQL 8 스테이징 구성
6. iOS Distribution Archive와 Android AAB 서명·설치
7. TestFlight / Play internal에서 실제 provider와 실기기 매트릭스 수행
8. 개인정보 문서·App Privacy·Data safety를 단일 데이터 맵으로 확정
9. 해당 계정이면 Play closed test 12명·14일 수행
10. 심사 계정·스토어 에셋·리뷰 노트를 준비하고 제출

## Release Gate

다음 항목을 모두 만족하기 전에는 production 제출하지 않는다.

- 영구 앱 ID, signing, provider 설정이 하나의 release identity로 연결돼 있다.
- Apple 로그인 탈퇴 revoke가 실제 계정으로 동작하고 외부 탈퇴 페이지가 canonical
  public domain·운영 SMTP·승인된 삭제/보유 정책에 연결돼 있다.
- 공유를 켜면 신고·필터·차단·연락처·운영 대응이 동작한다. 현재 off 후보는 서명 앱과
  모든 BE 인스턴스가 동일하게 off이고 기존 공유 데이터·알림을 노출하지 않음을 증명한다.
- App Privacy, Data safety, 개인정보처리방침이 실제 앱·SDK 동작과 일치한다.
- signed build의 iPhone/Android 알림 매트릭스와 실제 TMAP·FCM·APNs가 통과한다.
- MySQL 8 migration·다중 인스턴스·장애 복구가 통과한다.
- 인증 없이 `200`을 반환하는 liveness/readiness endpoint가 실제 orchestrator의
  배포 probe와 일치하고 비정상 차단·복구 증거가 있다.
- Prometheus metrics·rules가 실제 dashboard와 on-call에 연결되고 FE crash SDK가 동작한다.
- 심사 계정, backend, 지원 URL과 리뷰 노트가 심사 기간 동안 가용하다.

## 공식 정책 참고

- Apple account deletion: <https://developer.apple.com/support/offering-account-deletion-in-your-app/>
- Apple Sign in with Apple token revocation: <https://developer.apple.com/documentation/technotes/tn3194-handling-account-deletions-and-revoking-tokens-for-sign-in-with-apple>
- Apple UGC guideline 1.2: <https://developer.apple.com/app-store/review/guidelines/>
- Apple App Privacy: <https://developer.apple.com/app-store/app-privacy-details/>
- Google Play account deletion: <https://support.google.com/googleplay/android-developer/answer/13327111>
- Google Play UGC moderation: <https://support.google.com/googleplay/android-developer/answer/12923286>
- Google Play Data safety: <https://support.google.com/googleplay/android-developer/answer/10787469>
- Google Play new personal account testing: <https://support.google.com/googleplay/android-developer/answer/14151465>
