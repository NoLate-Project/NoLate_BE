# App Store & Google Play Release Roadmap

Last reviewed: 2026-07-25 KST

## 현재 판정

> **출시 준비 완료 아님.**

ETA·푸시 신뢰성 코드는 통합 자동검증과 독립 재감사를 통과했다. 그러나 영구 앱 ID, Apple token revoke, Google Play 외부 탈퇴 URL, UGC 안전장치, 서명 산출물, 실기기·실제 provider·MySQL 8 검증이 남아 있으므로 아직 production 제출 단계가 아니다.

## 상태 기준

- `완료`: 해당 범위가 통합됐고 독립 소스 검토와 전체 자동 테스트까지 통과했다.
- `부분 완료`: 코드와 자동 테스트는 있으나 실기기, 스테이징, 운영 또는 외부 콘솔 증거가 남았다.
- `미완료`: 실기기, 운영, 서명, 스토어 콘솔 등의 실행·검증 증거가 없다.
- `개발 필요`: FE, BE, DB, Web 또는 네이티브 소스 구현이 추가로 필요하다.

복합 작업은 가장 낮은 상태로 표시한다. 표는 중요도 순이며 `완료` 항목은 가장 아래에 둔다.

## 출시 준비 상태표

| 우선순위 | 작업 | App Store | Play Store | 소스 영향 | 상태 | 완료 조건 |
| --- | --- | --- | --- | --- | --- | --- |
| P0-01 | 영구 Bundle ID / Package Name과 provider 매핑 | 필수 | 필수 | FE 네이티브·빌드, 외부 SDK | 개발 필요 | `com.anonymous.*`를 영구 ID로 바꾸고 메인 앱·확장·App Group·Keychain·Firebase·소셜·지도/교통 provider를 동일 ID로 재연결 |
| P0-02 | Sign in with Apple 탈퇴 token revoke | 필수 | 해당 없음 | FE 인증, BE Apple 연동·보안 | 개발 필요 | authorization code 교환과 token 보관 정책을 정하고 탈퇴 시 Apple `/auth/revoke` 수행·실패 처리 |
| P0-03 | 앱 밖 계정 삭제 요청 URL | 선택 | 필수 | Web, BE API·보안 | 개발 필요 | 앱명, 삭제 범위, 보유 데이터·기간이 보이는 공개 URL에서 앱 없이 본인 확인 후 삭제 요청 가능 |
| P0-04 | 일정 공유 UGC 안전장치 또는 기능 비활성화 | 필수 | 필수 | FE, BE, DB, 운영 | 개발 필요 | 신고, 부적절 콘텐츠 필터, 사용자 차단, 공개 연락처·적시 운영 대응을 구현하거나 심사 빌드에서 공유 비활성화 |
| P0-05 | 운영 DB migration 체계 | 공통 운영 | 공통 운영 | BE, DB, CI/CD | 부분 완료 | 버전 SQL, production schema guard와 rollout runbook을 실제 MySQL 8에 적용하고 marker, roll-forward/rollback, backup/restore를 스테이징에서 검증 |
| P0-06 | Distribution Archive와 서명 AAB | 필수 | 필수 | FE 네이티브, CI, secret 운영 | 미완료 | 영구 ID의 iOS Distribution Archive와 Android release AAB를 생성·설치하고 스토어 사전 검사 통과 |
| P0-07 | 실기기 ETA·알림 acceptance | 필수 | 필수 | 실패 시 FE/BE 수정 | 미완료 | iPhone과 Android 12/13+에서 실제 TMAP·FCM·APNs로 상태·권한·액션 매트릭스 통과 |
| P0-08 | MySQL 8 다중 인스턴스·장애 복구 | 공통 운영 | 공통 운영 | BE 운영·DB | 미완료 | migration, 중복 scheduler, lock/deadlock, lease 만료, 프로세스 중단·재시작 통과 |
| P0-09 | Firebase·Apple·Google·Kakao·Naver·TMAP 운영 설정 | 필수 | 필수 | 주로 외부 콘솔, 일부 FE 설정 | 미완료 | release ID·SHA·인증서·redirect·API 제한·APNs key로 실제 로그인·지도·푸시 성공 및 Google Calendar OAuth 공개 앱 검증 |
| P0-10 | 심사 계정과 reviewer 경로 | 필수 | 필수 | 운영 데이터, 리뷰 노트 | 미완료 | 만료되지 않는 계정, 샘플 일정·공유 데이터, 비자명 기능 설명, 심사 기간 BE 가용성 준비 |
| P0-11 | 스토어 메타데이터와 에셋 | 필수 | 필수 | 에셋, 콘솔 | 미완료 | 지원·개인정보 URL, 설명, 연령 등급, iPhone 스크린샷, Play 아이콘·feature graphic·스크린샷 준비 |
| P0-12 | Play production access | 해당 없음 | 계정 조건부 필수 | Play Console | 미완료 | 2023-11-13 이후 생성 개인 계정이면 12명이 연속 14일 opted-in한 closed test 후 production access 신청 |
| P0-13 | 개인정보·약관·App Privacy·Data safety 정합성 | 필수 | 필수 | BE 법률 문서, FE fallback, 콘솔 | 부분 완료 | 위치·일정·검색·푸시 토큰·빠른 입력·Firebase/Groq 등 실제 SDK 흐름과 연령 정책을 단일 데이터 맵으로 대조하고 게시 |
| P0-14 | ETA 출처·신선도·동일 경로 비교 | 공통 | 공통 | FE, BE, DB | 부분 완료 | 통합 코드는 승인됨. 실제 TMAP에서 live 증가·감소·timeout·fallback·stale·경로 변경을 운영 DB/UI까지 검증 |
| P0-15 | 푸시 내구성·권한 fence·다기기 재시도 | 공통 | 공통 | FE, BE, DB | 부분 완료 | 통합 코드는 승인됨. 실제 FCM/APNs와 다중 인스턴스에서 중복·유실·계정 전환·부분 실패를 계측 |
| P0-16 | FE auth epoch·로컬 purge·신뢰도 UI | 필수 | 필수 | FE JS·native storage | 부분 완료 | 자동 테스트는 승인됨. 서명 빌드에서 로그아웃·탈퇴·재로그인·강제 종료·오프라인 복구 확인 |
| P1-01 | 버전·빌드 번호와 release config | 필수 | 필수 | FE app config, Xcode, Gradle, CI | 부분 완료 | 영구 ID 전환 후 앱·확장·Android 버전 정책을 CI에서 검사하고 실제 업로드로 확인 |
| P1-02 | 푸시·ETA 운영 관측과 호출 경보 | 권장 | 권장 | FE/BE metric·crash SDK, 운영 | 개발 필요 | actuator/micrometer 또는 동등 metric과 Crashlytics/Sentry를 붙이고 지연 job, lease, provider 실패율, ambiguous 발송에 dashboard·alert 연결 |
| P1-03 | HTTPS Universal Link / App Link | 권장 | 권장 | FE 네이티브, Web | 개발 필요 | AASA/assetlinks와 HTTPS 초대 링크를 제공하고 설치·미설치 fallback 검증 |
| P1-04 | Android adaptive icon | 해당 없음 | 권장 | FE Android resource/config | 부분 완료 | 원형·사각형 launcher와 Play listing에서 잘림 없는지 release 빌드로 확인 |
| P1-05 | FE build/CLI 공급망과 간접 lodash | 권장 | 권장 | FE lock, CI Node/native build | 개발 필요 | 남은 CLI/build critical 3·high 8과 앱 전이 lodash high 1을 호환 패치로 정리하고 Node 22/24 LTS의 native release build로 검증 |
| P2-01 | 반복 일정 | 제품 선택 | 제품 선택 | FE, BE, DB | 개발 필요 | 초기 출시 차단 항목은 아님. UT에서 수요 확인 후 발생·수정·push job 정책 구현 |
| P2-02 | 다중 시간대·DST | 제품 선택 | 제품 선택 | FE, BE, DB | 개발 필요 | 국내 MVP 비차단. 해외 확장 전 사용자 시간대·DST·종일 일정 규칙 구현 |

## 완료된 소스 검증

| 우선순위 | 작업 | 상태 | 증거 |
| --- | --- | --- | --- |
| 완료-01 | FE 신뢰성 통합 | 완료 | `680345135284d68b144091a31ab4df27a557dee2`: release config·typecheck 통과, lint 오류 0, 173 suites / 1,292 tests 통과 |
| 완료-02 | FE runtime HTTP dependency P0 | 완료 | Axios 1.18.1·form-data 4.0.6, direct runtime critical/high 0, 최소 lock diff 독립 감사 P0 0 / P1 0 |
| 완료-03 | BE ETA·푸시 통합 | 완료 | `3986d84552a162281986432d62295bc404153b09`: 768 tests 중 765 실행 통과, MySQL Docker 3건 조건부 스킵, 실패 0 |
| 완료-04 | BE exact commit 독립 감사 | 완료 | ETA 결합과 push 보안·상태 머신 두 감사 모두 P0 0 / P1 0 승인 |
| 완료-05 | 원본 변경 보호 | 완료 | 기존 dirty FE/BE 작업 트리를 유지하고 별도 integration worktree에서 통합 |

위 `완료`는 소스와 자동검증 범위다. 실기기·운영·콘솔 게이트가 남아 있으므로 앱 전체 출시 준비가 완료됐다는 뜻은 아니다.

## 실제 소스에 영향을 주는 묶음

### 1. 앱 ID·서명·provider

| 변경 | FE | BE | DB / Web |
| --- | --- | --- | --- |
| 영구 앱 ID | app config, Android namespace/applicationId, Xcode targets, App Group, Keychain, Firebase 파일 | 허용 redirect/client 검증이 있으면 함께 변경 | provider 콘솔과 association 파일 |
| Apple revoke | authorization code를 서버에 안전하게 전달 | token exchange, 보관/암호화 정책, revoke | token 저장이 필요하면 migration |
| release signing | Xcode/Gradle/CI secret 참조 | 없음 | App Store Connect / Play Console |

### 2. 계정·UGC·법률

| 변경 | FE | BE | DB / Web |
| --- | --- | --- | --- |
| Play 외부 탈퇴 | 설정의 공개 URL 연결은 선택 | 기존 탈퇴 use case 재사용 또는 제한된 web API | 공개 페이지, 본인 확인, rate limit |
| 신고·차단 | 일정/사용자 신고, 차단·해제 UI | 신고 처리, 공유·초대·기존 접근 집행 | 신고·차단 schema와 운영 조회 |
| 공유 비활성화 대안 | 심사 빌드에서 공유 진입 제거 | 공유 API 정책 차단 권장 | 기존 공유 데이터 처리 결정 |
| 개인정보 정합성 | 앱 내 fallback 문서, 권한 문구 | 법률 문서와 보유·삭제 정책 | 두 스토어 privacy 선언 |

### 3. ETA·알림 운영 검증

| 변경 | FE | BE | DB / 운영 |
| --- | --- | --- | --- |
| provenance UI | live/fallback, stale, 갱신 시각, confidence | source/fetchedAt/failure/fingerprint 계산 | job 상태 보관 |
| delivery reliability | 권한·토큰 복구, 탭·액션, 계정 전환 | outbox, delivery, history, dispatch fence, partial retry | lease·상태·지표 |
| 장애 대응 | 사용자에게 명확한 degraded 상태 표시 | timeout, ambiguous, retry/supersede 정책 | dashboard, alert, runbook |

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
2. Apple revoke, Play 외부 탈퇴 URL, UGC 구현 또는 공유 비활성화
3. DB migration 체계와 MySQL 8 스테이징 구성
4. iOS Archive와 Android AAB 서명·설치
5. TestFlight / Play internal에서 실제 provider와 실기기 매트릭스 수행
6. 개인정보 문서·App Privacy·Data safety를 단일 데이터 맵으로 확정
7. 해당 계정이면 Play closed test 12명·14일 수행
8. 심사 계정·스토어 에셋·리뷰 노트를 준비하고 제출

## Release Gate

다음 항목을 모두 만족하기 전에는 production 제출하지 않는다.

- 영구 앱 ID, signing, provider 설정이 하나의 release identity로 연결돼 있다.
- Apple 로그인 탈퇴 revoke와 Play 외부 탈퇴 URL이 실제 계정으로 동작한다.
- 공유가 켜져 있으면 신고·필터·차단·연락처·운영 대응이 동작한다.
- App Privacy, Data safety, 개인정보처리방침이 실제 앱·SDK 동작과 일치한다.
- signed build의 iPhone/Android 알림 매트릭스와 실제 TMAP·FCM·APNs가 통과한다.
- MySQL 8 migration·다중 인스턴스·장애 복구가 통과한다.
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
