# App Store & Google Play Release Roadmap

Last reviewed: 2026-07-24 KST

## Goal

NoLate MVP를 App Store와 Google Play에 제출할 수 있는 상태로 만들고, 심사 반려 가능성이 큰 기능·정책·빌드 공백을 출시 전에 제거한다.

이 문서는 스토어 출시 준비 중 **실제 소스 또는 빌드 설정에 영향을 주는 작업**을 중심으로 관리한다. App Store Connect, Play Console, Google Cloud Console에서만 수행하는 작업도 릴리스 게이트와 연결되는 경우 함께 기록한다.

## Status Legend

- `완료`: 코드와 실제 제출 산출물 또는 운영 환경에서 검증 완료
- `진행 중`: 일부 구현 또는 설정은 있으나 완료 조건을 충족하지 못함
- `미착수`: 구현이나 설정이 아직 없음
- `결정 필요`: MVP 포함 여부 또는 정책 방향을 먼저 확정해야 함
- `확인 필요`: 외부 콘솔 상태를 저장소만으로 확인할 수 없음

## P0: Required Before Store Submission

| ID | 작업 | App Store | Play Store | 소스 영향 | 현재 상태 | 완료 조건 |
| --- | --- | --- | --- | --- | --- | --- |
| REL-01 | 영구 Bundle ID / Package Name 확정 | 필수 | 필수 | FE 네이티브·빌드 설정, 외부 SDK 설정 | 미착수 | `com.anonymous.*`를 영구 ID로 교체하고 Firebase, Google, Apple, Kakao, Naver, TMAP 설정까지 동일 ID로 연결 |
| REL-02 | 버전·빌드 번호 통일 및 검증 강화 | 필수 | 필수 | FE 빌드 설정과 검증 스크립트 | 진행 중 | `app.json`, iOS 메인 앱, 공유 확장, Android의 버전 값을 릴리스 규칙에 맞추고 타깃별 불일치 시 CI 실패 |
| REL-03 | 스토어 서명과 실제 배포 산출물 생성 | 필수 | 필수 | 빌드 설정·CI·비밀정보 운영 | 미착수 | iOS Distribution Archive와 Android 서명 AAB를 생성하고 각 스토어의 사전 검증 통과. 키와 비밀번호는 Git에 저장하지 않음 |
| REL-04 | Sign in with Apple 탈퇴 토큰 철회 | 필수 | 해당 없음 | FE 로그인·탈퇴, BE 인증·DB·Apple 연동 | 미착수 | Apple 로그인 시 server-side token exchange에 필요한 값을 처리하고 회원탈퇴 시 Apple REST API revoke 성공 후 계정 정리 |
| REL-05 | 외부 회원탈퇴 웹 경로 제공 | 권장 | 필수 | 웹 페이지·BE API·보안·운영 | 미착수 | 앱을 재설치하지 않아도 본인 확인 후 탈퇴를 요청할 수 있는 공개 URL 제공. 페이지에 앱명, 삭제 범위, 보유 데이터와 처리 기간 표시 |
| REL-06 | 일정 공유 UGC 대응 방향 확정 | 필수 | 필수 | 제품 범위와 FE/BE 동작 | 결정 필요 | `신고·차단·운영 처리`를 구현하거나, 해당 기능이 준비될 때까지 MVP에서 사용자 간 일정 공유를 비활성화 |
| REL-07 | 일정·사용자 신고 기능 | 필수 | 필수 | FE UI, BE API, DB migration, 운영 도구 | REL-06에 종속 | 공유 기능 유지 시 일정/사용자 신고, 신고 사유, 처리 상태, 반복 위반 조치와 운영 조회 경로 구현 |
| REL-08 | 공유 사용자 차단과 권한 집행 | 필수 | 필수 | FE UI, BE API, DB migration, 공유 도메인 | REL-06에 종속 | 차단/해제, 신규 초대·공유 차단, 기존 공유 접근 정책과 관련 테스트 구현 |
| REL-09 | Google Calendar OAuth 공개 앱 준비 | 필수 | 필수 | FE 환경·인증 흐름 일부, Google Cloud 설정 | 확인 필요 | 플랫폼별 OAuth client/redirect 설정을 검증하고 `calendar.readonly` 민감 범위 공개 앱 검증 완료 |
| REL-10 | 개인정보·약관과 실제 데이터 흐름 일치 | 필수 | 필수 | BE 법률 문서, FE fallback 문서, 스토어 콘솔 | 진행 중 | 위치, 일정, 검색, 푸시 토큰, 빠른 입력, Firebase/Groq 등 처리 내용을 일치시키고 국외 이전·보유·삭제 내용을 검토 |
| REL-11 | 심사용 데모 계정과 리뷰 경로 | 필수 | 필수 | 운영 데이터 중심, 필요 시 데모 모드 | 미착수 | 만료되지 않는 심사용 계정, 샘플 일정/공유 데이터, 권한·기능별 심사 안내와 심사 기간 운영 BE 가용성 확보 |
| REL-12 | 최종 릴리스 실기기 스모크 테스트 | 필수 | 필수 | 코드 수정은 실패 발견 시 발생 | REL-03에 종속 | 스토어 서명 빌드로 가입·로그인·일정·경로·권한·푸시·공유·탈퇴를 실기기에서 통과 |

## Source Impact Breakdown

### Runtime Feature Changes

| 작업 | 플랫폼 | FE | BE | DB / Web | 비고 |
| --- | --- | --- | --- | --- | --- |
| Apple 탈퇴 토큰 철회 | App Store | Apple 로그인·탈퇴 요청 보강 | token exchange/revoke와 실패 처리 | 토큰 저장이 필요하면 암호화 저장 구조 추가 | 현재 `authorizationCode`는 인증 판단에 사용하지 않음 |
| 외부 회원탈퇴 페이지 | Play Store | 앱 내 링크는 선택 | 본인 확인·탈퇴 요청 API 재사용 또는 보강 | 공개 페이지와 보안·rate limit 필요 | 현재 예정 URL은 정상 페이지가 아님 |
| UGC 신고 | 양쪽 | 일정/사용자 신고 UI | 신고 생성·조회·처리 API | 신고 테이블과 운영 조회 | 공유 기능 유지 시 필요 |
| 사용자 차단 | 양쪽 | 차단·해제 UI와 상태 표시 | 공유·초대·접근 권한 집행 | 차단 관계 테이블 | API뿐 아니라 기존 공유 접근 차단 테스트 필요 |
| 14세 미만 정책 | 양쪽 | 연령 확인 또는 보호자 동의 UX | 가입 정책과 동의 이력 | 정책 선택에 따라 스키마 추가 | MVP를 14세 이상으로 제한할지 먼저 결정 |
| 플랫폼별 Google OAuth | 양쪽 | client ID와 redirect 처리 | 현재 구조에 따라 검증 보강 | Google Cloud 설정 | 민감 범위 검증 자체는 콘솔 작업 |

### Build and Native Configuration Changes

| 작업 | 영향 파일/영역 | 완료 기준 |
| --- | --- | --- |
| 영구 앱 ID | `NoLate_FE/app.json`, Android Gradle, Xcode project, entitlements, 공유 확장 | 모든 타깃과 외부 제공자 설정이 동일한 영구 ID 사용 |
| 빌드 번호 | `app.json`, Android `versionCode`, Xcode 메인/확장 `CURRENT_PROJECT_VERSION` | 타깃별 정책에 맞게 자동 증가하고 불일치 검증 |
| 릴리스 검증 | `NoLate_FE/scripts/verify-release-config.mjs` | 단순 문자열 개수 대신 iOS 타깃별 값과 Android 값을 명시적으로 검사 |
| Android 서명 | Android Gradle, CI secrets, 로컬 비추적 설정 | `bundleRelease` 성공 및 Play Console 업로드 검증 |
| Firebase·소셜 로그인 | Firebase 설정 파일, URL scheme, provider console | release Bundle ID/Package/SHA로 실기기 로그인 성공 |
| Universal/App Links | iOS associated domains, Android intent filter, 웹 association 파일 | 앱 미설치 시 웹/스토어 fallback, 설치 시 앱 내 초대 화면 이동 |
| Android adaptive icon | Android 리소스 또는 Expo app config | 원형·사각형 런처에서 잘림 없이 표시 |
| Expo/native 동기화 | app config, native project, package version | 설정의 source of truth와 prebuild 사용 여부를 문서화하고 `expo-doctor` 통과 |

### Legal Document Changes Stored in Source

다음 내용은 Store Console 입력만으로 끝나지 않고 저장소의 법률 문서도 함께 수정해야 한다.

- 개인정보 국외 이전 대상, 국가, 항목, 목적, 이전 시점·방법, 보유기간
- Firebase, Groq, 지도·교통·소셜 로그인 제공자의 데이터 처리 범위
- 회원탈퇴 시 삭제되는 데이터와 법적 사유로 보유되는 데이터
- 사용자 생성 콘텐츠의 금지 행위, 신고, 차단, 제재 및 문의 경로
- 만 14세 미만 가입 허용 여부와 동의 방식
- App Privacy와 Play Data safety 선언에 대응하는 실제 데이터 흐름

Primary source:

- `NoLate_BE/src/main/kotlin/com/noLate/legal/domain/LegalDocuments.kt`
- `NoLate_FE/src/api/legal.ts`

## Console and Operations Tasks Without Mandatory Runtime Changes

| 작업 | App Store | Play Store | 외부 콘솔/운영 |
| --- | --- | --- | --- |
| 개인정보 수집 선언 | App Privacy | Data safety | 실제 앱·SDK 동작과 동일해야 함 |
| 연령 등급 설문 | 필수 | 필수 | UGC, 위치, AI 입력 기능 반영 |
| 민감 OAuth 검증 | 간접 영향 | 간접 영향 | Google Cloud Console |
| 심사 메타데이터 | 설명, 키워드, 지원 URL, 리뷰 노트 | 설명, 카테고리, App content | 코드 변경 없음 |
| 스토어 이미지 | iPhone 스크린샷과 아이콘 | 512 아이콘, feature graphic, 스크린샷 | 에셋 제작·업로드 |
| Play 비공개 테스트 | 해당 없음 | 계정 조건부 필수 | 2023-11-13 이후 생성한 개인 계정은 12명·14일 요건 확인 |
| Play App Signing | 해당 없음 | 필수 | Play Console과 업로드 키 관리 |
| API 키 제한 | 권장 | 권장 | Firebase/Google/Kakao/Naver/TMAP/ODsay 콘솔에서 앱 ID·SHA·쿼터 제한 |
| 배포 국가·가격·출시일 | 필수 | 필수 | 각 스토어 콘솔 |

## P1: Recommended Before Public Release

| ID | 작업 | 플랫폼 | 소스 영향 | 완료 조건 |
| --- | --- | --- | --- | --- |
| REL-13 | HTTPS Universal Link / App Link | 양쪽 | 네이티브 설정·웹 | `nolate://`만 사용하는 초대 링크를 HTTPS 기반으로 보강 |
| REL-14 | Android adaptive icon과 스토어 에셋 | Play Store | 에셋·앱 설정 | 런처 아이콘과 Play listing 에셋 검증 |
| REL-15 | Crash/ANR 모니터링 | 양쪽 | SDK·앱 설정·개인정보 문서 | release 환경 크래시 수집과 알림, 개인정보 선언 일치 |
| REL-16 | 명시적 DB migration과 복구 절차 | 공통 운영 | BE 설정·migration source | 운영 `ddl-auto: update` 의존 제거, backup/restore 검증 |
| REL-17 | 공개 클라이언트 키 제한 | 양쪽 | 주로 외부 콘솔, 필요 시 BE proxy | 앱에 포함되는 키에 package/bundle/SHA/쿼터 제한 |
| REL-18 | 릴리스 자동화 | 양쪽 | CI·스크립트 | 테스트, 버전, 서명, 산출물 검증을 반복 가능한 명령으로 실행 |

## Recommended Sequence

1. 영구 Bundle ID와 Package Name을 확정한다.
2. iOS/Android 버전·빌드 번호와 릴리스 검증 규칙을 정리한다.
3. Android 업로드 키와 iOS Distribution signing을 준비해 실제 AAB/Archive를 생성한다.
4. Apple 탈퇴 토큰 철회와 외부 회원탈퇴 웹 경로를 구현한다.
5. 공유 기능을 MVP에 유지할지 결정하고, 유지하면 신고·차단·운영 처리를 구현한다.
6. Google Calendar OAuth 민감 범위 검증을 신청한다.
7. 개인정보처리방침·이용약관·App Privacy·Data safety를 동일한 데이터 맵으로 정리한다.
8. 심사용 계정과 스토어 메타데이터를 준비한다.
9. TestFlight와 Play internal/closed track에서 최종 실기기 스모크 테스트를 수행한다.

## Release Gate

아래 조건을 모두 만족하기 전에는 production 제출을 진행하지 않는다.

- 영구 앱 ID와 provider 설정이 확정되어 있다.
- iOS Archive와 Android AAB가 release signing으로 생성되고 설치된다.
- 계정 생성, 소셜 로그인, 회원탈퇴와 재가입 정책이 정상 동작한다.
- Apple 로그인 회원탈퇴 시 provider token 철회가 수행된다.
- Play Console에 입력할 외부 탈퇴 URL이 정상 응답하고 앱 없이 요청 가능하다.
- 공유 기능이 활성화되어 있다면 신고·차단·운영 대응이 동작한다.
- App Privacy, Data safety, 개인정보처리방침의 데이터 항목이 실제 동작과 일치한다.
- 심사용 계정으로 핵심 기능에 제한 없이 접근할 수 있다.
- foreground/background/terminated 푸시와 알림 액션이 스토어 서명 빌드에서 검증됐다.
