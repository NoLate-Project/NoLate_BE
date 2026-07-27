# App Store & Google Play Release Roadmap

Last reviewed: 2026-07-27 KST

## 현재 판정

> **출시 준비 완료 아님.**

ETA·푸시와 일정 공유, Apple 탈퇴 revoke, 앱 밖 계정 삭제,
liveness/readiness, BE metric·alert rule, FE 공급망 소스가 통합됐다. A–D 회귀와
FE 자동검증, iOS Simulator·Android API 35의 로컬 Release 실행도 통과했다. 직전
출시 검증 기준 exact는 BE `d3a9c038`, FE `77ca5e4`이며 A–D는 모두
skip·fail·error 0이다. 이 과거 exact에서 완료된 통합 변경 범위의 독립감사는
P0/P1 0(P2 테스트 견고성 3)이었다. 과거 BE exact는 host 전체 160 suites /
941 tests와 Docker 무캐시 build, MySQL 8.4 fresh create→same-DB validate 및 HTTP
계약까지 통과했다. 2026-07-27 전체 출시 gap 감사에서는 Release env·Google OAuth·
Android 표시명, ETA transaction·scheduler 격리, migration·orchestrator 자동화 등
미구현 항목을 아래 canonical backlog로 분리했다. 기존 독립감사 승인은 이 신규
백로그의 완료를 뜻하지 않는다. 영구 앱 ID와 배포 서명, 실제 provider·실기기와
운영 증거도 남아 있어 production 제출 단계가 아니다.
2026-07-27 일정 공유 복구에서는 FE/BE 기본값을 활성화하고, 공유 범위별 Redis 월
캐시, FK 없는 전용 revision row의 동일 transaction 무효화, 5개월 슬라이딩
프리패치와 실제 공유 변경 경로 검증을 추가했다. 한 transaction의 audience를 합쳐
ID 오름차순으로 한 번만 잠그고 Redis는 월 payload만 보관하므로 eviction·재시작이 이전
generation을 되살리지 않는다. 운영 전에는 전 인스턴스를 중단한 migration/backfill
cutover가 필요하다. UGC 신고·차단·운영 대응과 store-signed 실기기 증거도 여전히 출시
gate로 남는다. 이번 공유 복구 후보의 exact는 이 문서를 포함한 BE `main` 커밋과
아래 `완료-18`에 연결할 FE `main` 커밋이며, 직전 exact의 테스트 수와 감사 판정을
이번 후보의 증거로 재사용하지 않는다.

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
| P0-03 | 앱 밖 계정 삭제 요청 URL | 선택 | 필수 | Web, BE API·보안 | 부분 완료 | `caa9ba2`의 공개 페이지, `COMMON` email 본인 확인, rate limit, single-use grant, cleanup과 기본 disabled/fail-closed가 BE exact `d3a9c038`에 포함됨. canonical public domain·SMTP·정책 승인, 실제 계정 검증과 Google Play Console URL 등록은 남음 |
| P0-04 | 일정 공유 UGC 안전장치와 운영 검증 | 필수 | 필수 | FE, BE, DB, 운영 | 부분 완료 | 공유 기능과 일정·카테고리·공유 캘린더 경로를 기본 활성화하고, 공유 범위별 Redis v2 캐시·revision 무효화·월 프리패치를 복구했다. 신고·차단·필터·운영 대응, HTTPS 초대 링크, store-signed·prod 증거는 남음 |
| P0-05 | 운영 DB migration 체계 | 공통 운영 | 공통 운영 | BE, DB, CI/CD | 부분 완료 | 버전 SQL과 production schema guard는 있으나 ordered/checksummed manifest와 predecessor MySQL CI는 없음. rollout runbook을 실제 MySQL 8에 적용하고 marker, roll-forward/rollback, backup/restore를 스테이징에서 검증 |
| P0-06 | Distribution Archive와 서명 AAB | 필수 | 필수 | FE 네이티브, CI, secret 운영 | 미완료 | iOS Simulator Release와 Android debug-key signed Release APK는 실행됐지만 Android Release label은 현재 `NoLate_FE`임. 영구 ID·`NoLate` label의 iOS Distribution Archive와 Android release AAB를 생성·설치하고 스토어 사전 검사 통과 |
| P0-07 | 실기기 ETA·알림 acceptance | 필수 | 필수 | 실패 시 FE/BE 수정 | 미완료 | iOS Simulator Release의 로그인 렌더·PID 생존·crash 0과 Android API 35 Release APK의 cold 로그인 렌더·안정성은 통과함. iPhone과 Android 12/13+ 물리 기기에서 실제 ODsay·TMAP·FCM·APNs 상태·권한·액션 매트릭스 통과 |
| P0-08 | MySQL 8 다중 인스턴스·장애 복구 | 공통 운영 | 공통 운영 | BE 운영·DB | 부분 완료 | exact `d3a9c038`에서 MySQL 8.4 explicit 3 suites / 5 tests가 skip·failure·error 0이고 Apple `VARCHAR(16384)` metadata는 `ascii`/`ascii_bin`/`16384`로 확인됨. exact Docker image도 fresh create 31 tables→same-DB validate로 재기동됨. ETA provider I/O 트랜잭션 분리, scheduler executor 격리, 2-process crash/recovery harness는 없음 |
| P0-09 | Firebase·Apple·Google·Kakao·Naver·TMAP/ODsay 운영 설정 | 필수 | 필수 | 주로 외부 콘솔, 일부 FE 설정 | 미완료 | clean Release가 소비할 중앙 env/provider manifest와 플랫폼별 Google Calendar OAuth client 분리가 미완료임. release ID·SHA·인증서·redirect·API 제한·APNs key로 실제 로그인·지도·푸시 성공 및 OAuth 공개 앱 검증 |
| P0-10 | 심사 계정과 reviewer 경로 | 필수 | 필수 | 운영 데이터, 리뷰 노트 | 미완료 | 만료되지 않는 계정, 샘플 일정, 일정·카테고리·공유 캘린더 흐름을 포함한 비자명 기능 설명과 심사 기간 BE 가용성 준비 |
| P0-11 | 스토어 메타데이터와 에셋 | 필수 | 필수 | 에셋, 콘솔 | 미완료 | 지원·개인정보 URL, 설명, 연령 등급, iPhone 스크린샷, Play 아이콘·feature graphic·스크린샷 준비 |
| P0-12 | Play production access | 해당 없음 | 계정 조건부 필수 | Play Console | 미완료 | 2023-11-13 이후 생성 개인 계정이면 12명이 연속 14일 opted-in한 closed test 후 production access 신청 |
| P0-13 | 개인정보·약관·App Privacy·Data safety 정합성 | 필수 | 필수 | BE 법률 문서, FE fallback, 콘솔 | 부분 완료 | 법률 문서와 FE fallback은 있으나 privacy data inventory CI는 없음. 위치·일정·검색·푸시 토큰·빠른 입력·Firebase/Groq 등 실제 SDK 흐름과 연령 정책을 단일 데이터 맵으로 대조하고 게시 |
| P0-14 | ETA 출처·신선도·동일 경로 비교 | 공통 | 공통 | FE, BE, DB | 부분 완료 | 통합 코드는 승인됨. 실제 ODsay 대중교통 우선 호출·TMAP fallback과 TMAP 주행 경로에서 live 증가·감소·timeout·fallback·stale·경로 변경·양쪽 실패를 운영 DB/UI까지 검증 |
| P0-15 | 푸시 내구성·권한 fence·다기기 재시도 | 공통 | 공통 | FE, BE, DB | 부분 완료 | 통합 코드는 승인됨. 실제 FCM/APNs와 다중 인스턴스에서 중복·유실·계정 전환·부분 실패를 계측 |
| P0-16 | FE auth epoch·로컬 purge·신뢰도 UI | 필수 | 필수 | FE JS·native storage | 부분 완료 | 자동 테스트와 Simulator/AVD 더미 인증 cleanup은 확인됨. 서명 실기기에서 로그아웃·탈퇴·재로그인·계정 전환·강제 종료·오프라인 복구 확인 |
| P0-17 | 배포 health check 계약 | 공통 운영 | 공통 운영 | BE, 배포 | 부분 완료 | 인증 없는 liveness/readiness `ad42618`, deploy probe `58a2b43`, GET-only 보호 `b0d6c52`가 통합됨. exact Docker에서 3경로 `200`·`no-store`·`UP`, HEAD/POST/PUT/PATCH/DELETE/OPTIONS `401` 확인. target deploy manifest와 graceful drain 계약, 실제 orchestrator 장애·복구 증거는 없음 |
| P1-01 | 버전·빌드 번호와 release config | 필수 | 필수 | FE app config, Xcode, Gradle, CI | 부분 완료 | 값이 package/app/Gradle/Xcode에 분산되고 native CI는 Debug 중심임. iOS 앱·공유 확장의 marketing/build version을 일치시키고 Android versionName/versionCode를 단일 정책으로 관리하며 App Store Connect·Play Console의 기존 최고 업로드 번호보다 큰지 artifact와 실제 업로드로 확인 |
| P1-02 | 출시 최소 운영 관측과 호출 경보 | 권장 | 권장 | FE/BE metric·crash SDK, 운영 | 부분 완료 | `187619c`의 현행 Micrometer/Prometheus metrics·alert rules는 push/ETA 중심임. Apple·삭제·API·DB까지의 실제 수집·최소 dashboard·on-call routing·alert firing과 FE crash SDK는 남고 고급 장기 리포트는 `POST-02`로 분리 |
| P1-03 | HTTPS Universal Link / App Link | 권장 | 권장 | FE 네이티브, Web | 개발 필요 | 활성화된 공유 초대를 위해 AASA/assetlinks와 HTTPS 초대 링크를 제공하고 설치·미설치 fallback 검증 |
| P1-04 | Android adaptive icon | 해당 없음 | 권장 | FE Android resource/config | 부분 완료 | bitmap launcher·round icon은 있으나 adaptive foreground/background resource는 없음. 원형·사각형 launcher와 Play listing에서 잘림 없는지 release 빌드로 확인 |
| P1-05 | FE build/CLI 공급망과 간접 lodash | 권장 | 권장 | FE lock, CI Node/native build | 부분 완료 | 공급망·toolchain `d997ad4` 감사 critical 0, high 1은 production bundle에 포함되지 않는 legacy build-tool chain에만 남고 TMAP SDK archive digest는 고정되지 않음. FE 178 suites / 1,392 tests, lint 오류 0·경고 166과 로컬 Release 통과. Distribution Archive·서명 AAB·스토어 검증은 남음 |
| P2-01 | 반복 일정 | 제품 선택 | 제품 선택 | FE, BE, DB | 개발 필요 | 초기 출시 차단 항목은 아님. UT에서 수요 확인 후 발생·수정·push job 정책 구현 |
| P2-02 | 다중 시간대·DST | 제품 선택 | 제품 선택 | FE, BE, DB | 개발 필요 | 국내 MVP 비차단. 해외 확장 전 사용자 시간대·DST·종일 일정 규칙 구현 |
| P2-03 | 제품 지표·개인화·장기 코호트 | 제품 선택 | 제품 선택 | FE, BE, 운영 분석 | 미완료 | 출시 후 등록 완료율·ETA 신뢰도·클릭·출발 전환·지각 감소를 장기 코호트로 측정하고 고급 dashboard·자동 정기 리포트와 개인화의 입력으로 사용 |

## 후속 구현·출시 실행 백로그

기존 P0/P1/P2는 release gate 집계이고, 아래 표는 그 gate를 실행 가능한 원자 작업으로
분해한 canonical backlog다. 우선순위는 연결된 상위 gate를 상속한다. 경로의 `FE`는
FE integration repository, `BE`는 현재 repository를 뜻한다.

- `착수 가능`: 현재 저장소 정보만으로 구현과 자동 검증을 시작할 수 있다.
- `골격 착수`: fail-closed 골격과 CI는 시작할 수 있으나 최종 검증에는 외부 값이 필요하다.
- `선행값 대기`: identity, credential, infra 또는 승인 값이 있어야 완료할 수 있다.
- `조건부·출시 후`: 기능 활성화 조건이 생기거나 출시 후 주기 검증 시점에 수행한다.

`조건부·출시 후` ID 중 공유 기능에 연결된 항목은 공유 기본 활성화 복구에 따라 다시
현행 launch gate를 상속한다. 순수 출시 후 운영 항목만 `REL-01` 대상에서 제외한다.

### 소스 구현

| ID | 상태 | 연결 gate (Master / Store) | 작업 | 소스 근거 | Acceptance |
| --- | --- | --- | --- | --- | --- |
| SRC-01 | 선행값 대기 | Master `P0-01`, `P1-01` / Store `P0-01` | Permanent identity 고정 | `FE/app.json`<br>`FE/android/app/build.gradle` | release 입력·artifact의 `com.anonymous.*`가 0이고 bundle/package/App Group/Keychain/Firebase/provider ID가 승인된 `EXT-01` manifest와 일치 |
| SRC-02 | 골격 착수 | Master `P0-01`, `P0-07`, `P1-01` / Store `P0-01`, `P0-09`, `P1-01` | Release env/provider manifest와 clean preflight | `FE/src/api/env.ts`<br>`FE/scripts/verify-release-config.mjs` | clean checkout CI가 단일 platform/provider manifest를 소비하고 placeholder·localhost·필수 key/HTTPS 누락, public secret 또는 JS/native/extension 값 불일치를 bundle 전에 fail-closed하며 공유 기본값은 FE/BE가 일치 |
| SRC-03 | 골격 착수 | Master `P0-01`, `P0-07` / Store `P0-01`, `P0-09` | 플랫폼별 Google Calendar OAuth | `FE/src/api/env.ts`<br>`FE/src/modules/onboarding/googleCalendarImport.ts` | iOS·Android·Web이 각자의 client·redirect·audience와 PKCE 계약만 선택하고 다른 플랫폼 ID 혼합·누락은 CI에서 fail-closed하며 플랫폼별 실제 OAuth 통과 |
| SRC-04 | 착수 가능 | Master `P0-05`, `P0-13`, `P1-01` / Store `P0-06`, `P0-11`, `P1-01` | Android display name과 artifact 검사 | `FE/android/app/src/main/res/values/strings.xml`<br>`FE/android/app/src/main/AndroidManifest.xml` | merged Release manifest와 aapt/APK label이 정확히 `NoLate`이고 회귀 시 CI 실패 |
| SRC-05 | 착수 가능 | Master `P0-08`, `P0-10`, `P0-11` / Store `P0-08`, `P0-14`, `P0-15` | ETA provider I/O transaction 분리 | `BE/src/main/kotlin/com/noLate/schedule/application/service/SchedulePushJobWorker.kt`<br>`BE/src/main/kotlin/com/noLate/schedule/application/service/SchedulePushJobCoordinator.kt` | claim·persist는 짧은 독립 transaction이고 provider 호출 중 DB transaction·row lock이 없으며 timeout·동시성 회귀 통과 |
| SRC-06 | 착수 가능 | Master `P0-08`, `P0-11` / Store `P0-08`, `P0-15` | Scheduled executor 격리 | `BE/src/main/kotlin/com/noLate/NoLateApplication.kt`<br>`BE/src/main/kotlin/com/noLate/notification/application/service/PushOutboxDispatchWorker.kt` | ETA·outbox·route/reaper·account-deletion retention 중 하나의 지연이 다른 `@Scheduled` worker를 늦추지 않고 bounded queue·thread name·shutdown 검증 통과 |
| SRC-07 | 착수 가능 | Master `P0-08`, `P0-11`, `P0-16` / Store `P0-05`, `P0-08`, `P0-15` | Worker enablement/context 계약 | `BE/src/main/resources/application.yml`<br>`BE/src/main/resources/application-prod.yml` | global false는 모든 `@Scheduled` worker를 끄고 subsystem flag가 우회하지 못한다. Apple revoke·observability SmartLifecycle은 각자의 flag 경계를 유지하며 startup summary와 context matrix가 이를 검증 |
| SRC-08 | 착수 가능 | Master `P1-03` / Store `P1-03` | Universal/App Link client 계약 | `FE/app/_layout.tsx`<br>`FE/ios/NoLateFE/NoLateFE.entitlements`<br>`FE/android/app/src/main/AndroidManifest.xml`<br>`FE/src/modules/share/scheduleSharingPolicy.ts` | iOS associated domains와 Android HTTPS `autoVerify`를 적용해 allowlisted host/path만 열고 설치·미설치 fallback을 검증하며 invalid·expired·변조·명시적 sharing-off는 안전 경로로 닫힘 |
| SRC-09 | 골격 착수 | Master `P1-02`, `P0-09` / Store `P1-02`, `P0-13` | FE crash SDK·symbol·PII 계약 | `FE/package.json`<br>`FE/.github/workflows/ci.yml` | signed Release test crash가 dSYM/source map으로 symbolicate되고 token·location·calendar PII는 redaction되며 symbol upload 실패 시 CI 실패 |
| SRC-10 | 착수 가능 | Master `P0-09` / Store `P0-13` | Privacy data inventory CI | `FE/app/legal/privacy-policy.tsx`<br>`FE/ios/NoLateFE/PrivacyInfo.xcprivacy`<br>`FE/android/app/src/main/AndroidManifest.xml`<br>`BE/src/main/kotlin/com/noLate/legal/domain/LegalDocuments.kt` | machine-readable inventory가 SDK/provider·data·목적·보유·국외 이전·삭제를 포함하고 iOS PrivacyInfo·merged Android permission·법률 문서·App Privacy·Data safety drift 시 CI 실패 |
| SRC-11 | 착수 가능 | Master `P0-05`, `P0-07`, `P1-05` / Store `P0-06`, `P0-09`, `P1-05` | TMAP SDK digest 고정 | `FE/scripts/install-tmap-native-sdk.sh`<br>`FE/.github/workflows/ci.yml` | artifact별 version·SHA-256가 고정되고 mismatch·미등록 archive는 extract/build 전에 실패하며 digest 변경은 review 대상 |
| SRC-C01 | 착수 가능 | Master `P0-04` / Store `P0-04` | 활성 공유 UGC 안전장치 | `FE/src/modules/share/scheduleSharingPolicy.ts`<br>`BE/src/main/kotlin/com/noLate/schedule/application/service/ScheduleSharingAvailabilityPolicy.kt` | report·block·filter·운영 대응·기존 접근 통제와 provider·signed-device 검증을 완료하고 kill switch 복구 절차를 문서화 |
| POST-01 | 조건부·출시 후 | Master `P0-02` / Store `P0-02` | Apple 주기 credential 검증 | `BE/src/main/kotlin/com/noLate/auth/apple/AppleTokenLifecycleService.kt`<br>`BE/src/main/kotlin/com/noLate/auth/apple/AppleTokenRevocationWorker.kt` | 출시 후 정책 주기로 실제 provider credential을 검증하고 invalid·revoked는 fail-closed state·metric·manual action으로 전환하며 token plaintext를 기록하지 않음 |
| POST-02 | 조건부·출시 후 | Master `P1-02`, `P2-03` / Store `P1-02`, `P2-03` | 고급 운영·코호트 자동 리포트 | `BE/src/main/kotlin/com/noLate/global/observability/NoLateOperationalMetrics.kt`<br>`BE/ops/prometheus/nolate-release-alerts.yml` | 출시 최소 dashboard와 분리해 장기 코호트·지각 감소율·provider 추세를 자동 집계하는 고급 dashboard와 정기 리포트를 운영 수요·보유 정책 승인 후 추가 |

### 배포·운영

| ID | 상태 | 연결 gate (Master / Store) | 작업 | 소스 근거 | Acceptance |
| --- | --- | --- | --- | --- | --- |
| DEP-01 | 골격 착수 | Master `P0-05`, `P1-01`, `P1-05` / Store `P0-06`, `P1-01`, `P1-05` | Signed Archive/AAB CI와 version | `FE/.github/workflows/ci.yml`<br>`FE/scripts/verify-release-config.mjs` | PR의 unsigned Release compile과 보호된 tag/manual signed Archive·export/AAB를 분리한다. signed job 직전에 App Store Connect·Play Console의 현재 최고 업로드 번호를 다시 고정하고 iOS 앱·확장 version 일치, Android versionCode 정책과 최고값 초과, permanent ID·서명·entitlement·APNs·embedded env·Metro 비의존성을 artifact 내부에서 재검사해 TestFlight·Play internal 설치를 통과하며 secret을 남기지 않음 |
| DEP-02 | 착수 가능 | Master `P0-08`, `P0-16` / Store `P0-05`, `P0-08` | Ordered migration manifest와 CI | `BE/docs/member/migrations/`<br>`BE/.github/workflows/ci.yml` | ordered checksummed manifest의 gap·checksum drift가 실패하고 predecessor MySQL 8.4에서 apply→marker→validate와 rollback/restore rehearsal 통과 |
| DEP-03 | 골격 착수 | Master `P0-08`, `P0-15` / Store `P0-08`, `P0-17` | Orchestrator manifest와 graceful shutdown | `BE/Dockerfile`<br>`BE/docs/release/deployment-health-probes.md` | target manifest가 review되고 startup·readiness·liveness가 분리되며 의존성 장애는 readiness traffic 제거, 프로세스 교착은 liveness 재시작으로 처리하고 drain 전 readiness 하강·bounded graceful shutdown·termination grace·rolling recovery가 입증됨 |
| DEP-04 | 착수 가능 | Master `P0-08`, `P0-11` / Store `P0-08`, `P0-15` | Multiprocess crash/recovery harness | `BE/src/main/kotlin/com/noLate/schedule/application/service/SchedulePushJobCoordinator.kt`<br>`BE/src/main/kotlin/com/noLate/notification/application/service/PushOutboxDispatchWorker.kt` | MySQL·Redis와 BE 앱 2개 이상에서 ETA/outbox/provider lease owner를 kill해 bounded recovery, confirmed delivery 중복 0, stuck processing 0을 입증 |
| DEP-05 | 선행값 대기 | Master `P0-04` / Store `P0-04` | Production sharing-on·kill-switch 증거 | `BE/docs/release/schedule-sharing-production-off.md`<br>`BE/src/main/resources/application-prod.yml` | signed FE와 모든 target BE가 `ENABLED`로 일치하고 직접·카테고리·캘린더 공유/수정/회수 및 Redis 무효화가 동작하며, 명시적 false에서는 API·가시성·알림이 fail-closed임을 입증 |
| DEP-06 | 골격 착수 | Master `P0-02`, `P0-06`, `P0-07`, `P0-10`, `P0-11` / Store `P0-02`, `P0-07`, `P0-09`, `P0-14`, `P0-15` | Provider staging probe와 fail-fast | `FE/src/modules/map/tmapApi.ts`<br>`FE/src/modules/map/odsayApi.ts`<br>`BE/src/main/kotlin/com/noLate/schedule/infrastructure/TmapTrafficClient.kt`<br>`BE/src/main/kotlin/com/noLate/schedule/dev/SchedulePushScenarioRunner.kt` | Apple·ODsay·TMAP·FCM·APNs isolated staging probe가 redacted artifact를 남긴다. 대중교통 ODsay 우선 호출→TMAP fallback과 양쪽 실패를 구분하고 enabled provider의 blank key·비공식 origin/base URL은 FE release preflight 또는 BE production startup에서 역할별 차단하며 live/fallback/multi-device matrix 통과 |
| DEP-07 | 골격 착수 | Master `P0-03` / Store `P0-03` | 삭제 ingress client-IP·SMTP/domain | `BE/src/main/kotlin/com/noLate/accountdeletion/controller/AccountDeletionPageController.kt`<br>`BE/docs/release/account-deletion-rollout.md` | trusted-proxy allowlist의 canonical client IP만 사용해 spoofed forwarded header를 무시하고 edge·app rate limit, TLS domain·실 SMTP, enumeration-free single-use flow를 통과한 승인 URL을 Google Play Console에 등록 |
| DEP-08 | 골격 착수 | Master `P1-02` / Store `P1-02` | 출시 최소 metrics·dashboard·on-call 연결 | `BE/src/main/kotlin/com/noLate/global/observability/NoLateOperationalMetrics.kt`<br>`BE/ops/prometheus/nolate-release-alerts.yml` | Apple queue·삭제·API SLI·DB pool/lock/migration·ETA/push를 최소 dashboard에서 scrape하고 alert fire/resolve와 on-call 수신·ack, FE crash link를 입증하며 장기 분석·자동 리포트는 포함하지 않음 |
| DEP-09 | 착수 가능 | Master `P1-03` / Store `P1-03` | AASA/assetlinks hosting | `FE/app.json`<br>`FE/app/_layout.tsx` | canonical HTTPS `.well-known/apple-app-site-association`·`assetlinks.json`을 올바른 MIME·무 redirect·cache 정책으로 제공하고 signed 설치·미설치 검증 통과 |
| DEP-10 | 착수 가능 | Master `P1-04` / Store `P1-04` | Android adaptive icon | `FE/app.json`<br>`FE/android/app/src/main/AndroidManifest.xml` | adaptive foreground/background XML·resource가 있고 필요 시 monochrome을 제공하며 Release의 round·square·listing safe area 검증 통과 |
| REL-01 | 골격 착수 | Master 현행 활성 P0/P1 launch gate / Store 현행 활성 P0/P1 launch gate | Final exact release 재검증 | `FE/.github/workflows/ci.yml`<br>`BE/.github/workflows/ci.yml` | `조건부·출시 후`를 제외한 모든 현행 배포 변경을 합친 clean exact FE/BE commit에서 전체 테스트와 A–D가 skip·fail·error 0이다. CI run ID, FE/BE SHA, Archive/AAB SHA-256·version/build·signing identity, BE image digest를 하나의 provenance로 연결하고 TestFlight·Play internal 설치본과 배포 image가 그 exact artifact임을 증명한 뒤 독립감사 P0/P1 0을 기록한다. 기존 `완료-04/08` 증거는 새 후보에 재사용하지 않음 |

### 감사 보강

| ID | 상태 | 연결 gate (Master / Store) | 작업 | 소스 근거 | Acceptance |
| --- | --- | --- | --- | --- | --- |
| AUD-01 | 착수 가능 | Master `P0-08`, `P0-16` / Store `P0-05`, `P0-08` | Live DDL metadata assertion | `BE/src/test/kotlin/com/noLate/auth/apple/AppleTokenLifecycleMySqlMigrationTest.kt`<br>`BE/docs/member/migrations/2026-07-26-apple-token-lifecycle.sql` | 실제 MySQL `information_schema`의 type·length·charset·collation·constraint를 assert하고 불일치 시 실패 |
| AUD-02 | 착수 가능 | Master `P0-08` / Store `P0-08` | Docker skip exception 제한 | `BE/src/test/kotlin/com/noLate/auth/apple/AppleTokenLifecycleMySqlMigrationTest.kt`<br>`BE/.github/workflows/ci.yml` | 증명된 daemon·CLI 부재만 local skip 가능하고 CI Docker-required lane은 skip 0이며 build·schema·runtime·provider failure가 skip으로 바뀌지 않음 |
| AUD-03 | 착수 가능 | Master `P0-15` / Store `P0-17` | ENTRYPOINT semantics 검증 | `BE/Dockerfile`<br>`BE/src/test/kotlin/com/noLate/global/health/ContainerReadinessProbeTest.kt` | built image inspect·run으로 ENTRYPOINT·CMD·mainClass·UID·args를 검증하고 단순 문자열 포함이 아닌 semantic drift에서 실패 |
| AUD-04 | 착수 가능 | Master `P0-08`, `P0-11` / Store `P0-08`, `P0-15` | Scheduling context 검증 | `BE/src/main/kotlin/com/noLate/NoLateApplication.kt`<br>`BE/src/main/kotlin/com/noLate/schedule/application/service/SchedulePushJobWorker.kt` | context matrix가 모든 scheduler와 global/subsystem flag를 열거하고 test·migration·probe context에서 원치 않는 scheduled execution이 없음 |

### 외부 선행값

| ID | 상태 | 연결 gate (Master / Store) | 작업 | 소스 근거 | Acceptance |
| --- | --- | --- | --- | --- | --- |
| EXT-01 | 선행값 대기 | Master `P0-01` / Store `P0-01`, `P0-09` | Identity/provider map 승인 | `FE/app.json`<br>`BE/src/main/resources/application-prod.yml` | release identity·team·client·redirect와 Apple·Firebase·Google·Kakao·Naver·TMAP/ODsay mapping이 owner 승인됐고 문서에는 secret이 없음 |
| EXT-02 | 선행값 대기 | Master `P0-05`, `P0-13`, `P0-14` / Store `P0-06`, `P0-10`, `P0-12` | Signing/store 접근 | `FE/android/app/build.gradle`<br>`FE/ios/NoLateFE.xcodeproj/project.pbxproj` | cert·profile·upload key·store role·Play signing·test-track access와 양쪽 콘솔의 현재 최고 업로드 version/build 기준값이 secret manager와 담당자에게 준비됨 |
| EXT-03 | 선행값 대기 | Master `P0-03`, `P0-09` / Store `P0-03`, `P0-13` | Domain·SMTP·policy 승인 | `BE/docs/release/account-deletion-rollout.md`<br>`BE/src/main/kotlin/com/noLate/legal/domain/LegalDocuments.kt` | DNS·TLS·canonical domain·SMTP sender와 삭제 증명·보유·processor 문구가 legal/security 승인을 받음 |
| EXT-04 | 선행값 대기 | Master `P0-08`, `P0-15`, `P0-16` / Store `P0-05`, `P0-08`, `P0-17` | Orchestrator·staging DB·backup | `BE/Dockerfile`<br>`BE/docker-compose.yml` | target orchestrator class·namespace, production-like MySQL 8·Redis, backup target·KMS·access·maintenance window가 확정됨 |
| EXT-05 | 선행값 대기 | Master `P0-02`, `P0-06`, `P0-07` / Store `P0-02`, `P0-07`, `P0-09` | 실제 provider·OAuth console·device·account | `BE/src/main/resources/application-prod.yml`<br>`FE/src/api/env.ts` | 실제 Apple·Firebase/FCM/APNs·TMAP/ODsay credential/account와 origin·package·SHA 제한, 플랫폼별 Google Calendar·Kakao·Naver OAuth client·redirect·consent 설정·test account와 iPhone·Android 12/13+ device·test identity가 준비됨 |
| EXT-06 | 선행값 대기 | Master `P1-02` / Store `P1-02` | Telemetry·on-call 자원 | `BE/ops/prometheus/nolate-release-alerts.yml`<br>`FE/package.json` | telemetry project·DSN·scrape endpoint·dashboard workspace·on-call route·owner·runbook이 확정됨 |
| EXT-07 | 선행값 대기 | Master `P0-09`, `P0-13`, `P0-14` / Store `P0-10`, `P0-11`, `P0-12`, `P0-13` | Legal·store asset·tester 승인 | `BE/src/main/kotlin/com/noLate/legal/domain/LegalDocuments.kt`<br>`FE/app/legal/privacy-policy.tsx` | privacy/data map·console 선언·review account·asset·support URL·계정 유형 판정·tester 증거가 승인됨 |

### 권장 실행 순서

1. `EXT-01`을 확정하고 `SRC-01`·`SRC-02`·`SRC-03`·`SRC-04`·`SRC-11`을 닫은 뒤 `EXT-02`와 `DEP-01`로 signed artifact를 만든다.
2. 병렬로 `SRC-05`·`SRC-06`·`SRC-07`, `DEP-02`·`DEP-04`, `AUD-01`·`AUD-02`·`AUD-04`를 완료해 DB·worker 실행 기반을 고정한다.
3. `EXT-04` 후 `DEP-03`·`AUD-03`을 적용하고 target orchestrator에서 health·drain·recovery를 증명한다.
4. `EXT-03` 후 `DEP-07`, `EXT-05` 후 `DEP-05`·`DEP-06`으로 canonical deletion, sharing-on·kill-switch, 실제 provider·실기기 gate를 닫는다.
5. `EXT-06` 후 `SRC-09`·`DEP-08`, 소스만으로 가능한 `SRC-10`·`DEP-10`을 병렬 완료한다.
6. `EXT-07`로 privacy/store/reviewer 자료를 승인하고 전체 P0/P1 gate를 재판정한다.
7. 모든 현행 launch gate를 합친 뒤 `REL-01`로 exact FE/BE 전체 테스트, A–D와 store-signed artifact provenance를 최종 확인한다.
8. `SRC-08`·`DEP-09`·`SRC-C01`은 활성 공유 출시 gate로 수행하고, `POST-01~02`는 출시 후 운영 주기·수요 승인에 따라 수행한다.

## 완료된 소스 검증

| 우선순위 | 작업 | 상태 | 증거 |
| --- | --- | --- | --- |
| 완료-01 | FE 신뢰성 통합 | 완료 | `680345135284d68b144091a31ab4df27a557dee2`: release config·typecheck 통과, lint 오류 0, 173 suites / 1,292 tests 통과 |
| 완료-02 | FE runtime HTTP dependency P0 | 완료 | Axios 1.18.1·form-data 4.0.6, direct runtime critical/high 0, 최소 lock diff 독립 감사 P0 0 / P1 0 |
| 완료-03 | BE ETA·푸시 통합 | 완료 | `3986d84552a162281986432d62295bc404153b09`: 768 tests 중 765 실행 통과, MySQL Docker 3건 조건부 스킵, 실패 0 |
| 완료-04 | 완료된 통합 변경 범위 독립 재감사 | 완료 | BE `d3a9c038`·FE `77ca5e4`의 통합 변경 범위 감사에서 P0 0 / P1 0. 후속 전체 출시 gap은 위 `SRC`·`DEP`·`AUD` 백로그로 별도 추적 |
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
| 완료-18 | 일정 공유·장기 월 캐시 로컬 시뮬레이션 | 완료 | 실제 후보 서버 API에서 직접 일정과 카테고리 공유의 생성·권한 변경·수신자 수정·회수, 공유 캘린더 초대 수락·일정 생성·교차 수정을 확인했다. 2026-04~10의 scoped Redis 월 키 7개가 연속 적재됐고 Redis 중단 중 회수 후에도 이전 generation은 재노출되지 않았다. 메타데이터 cold 5.877초→warm 0.009초, TTL 24시간을 확인했다. FE `f7739dcbeb251dd47906047ed59819d9ea897c71`은 5개월 창의 전·후 3개월 초과 이동과 fresh-hit 무호출 회귀를 자동 검증했다. |
| 완료-19 | 일정 공유·durable cache 최종 소스 게이트 | 완료 | FE `f7739dcbeb251dd47906047ed59819d9ea897c71`: release config·typecheck, 180 suites / 1,406 tests, lint 오류 0(기존 경고 166). 이 문서를 포함한 BE `main` 커밋: clean host 165 suites / 963 tests, 4 skipped, failure·error 0 및 `bootWar` 통과. 공유 수신자 depart-now까지 durable revision 회귀에 포함했고 최종 독립감사 P0/P1 0. |

`완료-06`과 `완료-09`는 당시 production-off 후보에 대한 역사적 증거다. 현재 공유
기본 활성화 계약을 대체하지 않으며, 새 후보는 위 `P0-04`·`DEP-05` 기준으로 다시
검증한다.

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
| 공유 기능·캐시 | 일정·카테고리·공유 캘린더 UI와 5개월 슬라이딩 캐시, revision polling | 공유 기본 활성화, `owned/shared` Redis v2 namespace와 변경 audience 무효화, 명시적 false kill switch | 신고·차단·운영 대응과 store-signed·production 공유/회수/초대 증거 남음 |
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
| ODsay | 대중교통 우선 호출 성공, invalid credential·quota·timeout·malformed, TMAP fallback과 양쪽 실패의 provenance·UI 구분 |
| FCM/APNs | 다계정·다기기, invalid token, 일부 실패, timeout/불확실 응답, 앱 상태별 수신 |
| MySQL 8 | 명시적 migration, 두 scheduler 경쟁, lock/deadlock, lease heartbeat/만료, crash/restart |

## 제출 순서

1. 영구 앱 ID 확정 및 모든 provider 재매핑
2. Apple 실제 계정·credentials로 revoke를 검증하고 외부 탈퇴 페이지를 canonical
   domain·SMTP에 연결해 정책 승인을 완료
3. 공유 기능과 명시적 kill switch의 서명·운영 증거 확보
4. liveness/readiness probe를 실제 orchestrator에 적용하고 Prometheus
   dashboard·on-call·FE crash SDK 연결
5. DB migration 체계와 MySQL 8 스테이징 구성
6. iOS Distribution Archive와 Android AAB 서명·설치
7. TestFlight / Play internal에서 실제 provider와 실기기 매트릭스 수행
8. 개인정보 문서·App Privacy·Data safety를 단일 데이터 맵으로 확정
9. 해당 계정이면 Play closed test 12명·14일 수행
10. 심사 계정·스토어 에셋·리뷰 노트를 준비
11. 모든 변경이 합쳐진 exact FE/BE에서 전체 테스트와 A–D를 재실행하고
    store-signed Archive/AAB·TestFlight·Play internal 설치본과 BE 배포 image의
    source provenance를 확인해 독립감사 P0/P1 0을 기록한 뒤 제출

## Release Gate

다음 항목을 모두 만족하기 전에는 production 제출하지 않는다.

- 영구 앱 ID, signing, provider 설정이 하나의 release identity로 연결돼 있다.
- Apple 로그인 탈퇴 revoke가 실제 계정으로 동작하고 외부 탈퇴 페이지가 canonical
  public domain·운영 SMTP·승인된 삭제/보유 정책에 연결돼 있다.
- 활성 공유에서 신고·필터·차단·연락처·운영 대응이 동작하고, 서명 앱과 모든 BE
  인스턴스의 설정이 일치한다. 명시적 kill switch에서는 기존 공유 데이터·알림을
  노출하지 않음을 증명한다.
- App Privacy, Data safety, 개인정보처리방침이 실제 앱·SDK 동작과 일치한다.
- signed build의 iPhone/Android 알림 매트릭스와 실제 ODsay·TMAP·FCM·APNs가 통과한다.
- MySQL 8 migration·다중 인스턴스·장애 복구가 통과한다.
- 인증 없이 `200`을 반환하는 liveness/readiness endpoint가 실제 orchestrator의
  배포 probe와 일치하고 비정상 차단·복구 증거가 있다.
- Prometheus metrics·rules가 실제 출시 최소 dashboard와 on-call에 연결되고 FE crash SDK가 동작한다.
- 심사 계정, backend, 지원 URL과 리뷰 노트가 심사 기간 동안 가용하다.
- 모든 배포 변경을 합친 exact FE/BE commit의 전체 테스트와 A–D가 통과하고,
  제출할 store-signed artifact·TestFlight·Play internal 설치본·BE 배포 image의
  CI run ID·version/build·commit SHA·checksum/image digest·signing identity가 그
  source exact와 일치하며 독립감사 P0/P1 0이다.

## 공식 정책 참고

- Apple account deletion: <https://developer.apple.com/support/offering-account-deletion-in-your-app/>
- Apple Sign in with Apple token revocation: <https://developer.apple.com/documentation/technotes/tn3194-handling-account-deletions-and-revoking-tokens-for-sign-in-with-apple>
- Apple UGC guideline 1.2: <https://developer.apple.com/app-store/review/guidelines/>
- Apple App Privacy: <https://developer.apple.com/app-store/app-privacy-details/>
- Google Play account deletion: <https://support.google.com/googleplay/android-developer/answer/13327111>
- Google Play UGC moderation: <https://support.google.com/googleplay/android-developer/answer/12923286>
- Google Play Data safety: <https://support.google.com/googleplay/android-developer/answer/10787469>
- Google Play new personal account testing: <https://support.google.com/googleplay/android-developer/answer/14151465>
