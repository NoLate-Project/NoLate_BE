# 📦 Backend Base Project — MUST-HAVE Core Checklist (Push 포함)
> 어떤 서비스든 공통으로 필요한 핵심 기능만 남긴 베이스 백엔드 구성  
> (Push 알림 기능 포함 버전)

---

# ✅ 1. 필수 기능 요구사항 (Functional)

## 🔐 A. 인증/인가 (Authentication & Authorization)
- [x] 회원가입  
- [x] 로그인 (JWT Access Token 발급)  
- [x] Refresh Token 재발급  
- [x] 로그아웃 (Refresh Token 폐기 또는 Blacklist)  
- [x] JWT 인증 필터 (SecurityFilterChain)


---

## 👤 B. 사용자 관리 (User Management)
- [x] 사용자 프로필 조회  
- [x] 사용자 프로필 수정  
- [x] 비밀번호 변경  
- [x] 회원 탈퇴  

---

## 🔔 C. Push 알림 (Firebase)
- [x] FCM Token 저장  
- [x] 단일 사용자 Push 발송  
- [x] 다중 사용자 Push 발송  
- [x] 예약 Push 발송(스케줄러 연동)  
- [x] FCM 서버 통신 모듈(WebClient or RestTemplate)

---

## 📤 D. 파일 업로드 (File Upload)
- [ ] Presigned URL 발급  
- [ ] 파일 업로드  
- [ ] 파일 삭제  
- [ ] MIME 타입 검사  
- [ ] 파일 크기 검증  

---

# 🔥 2. 필수 비기능 요구사항 (Non-Functional)

## 🔒 A. 보안(Security)
- [ ] 비밀번호 해싱(BCrypt)  
- [ ] JWT Secret Key 환경변수 관리  
- [ ] CORS 정책 설정  
- [ ] API Key 환경변수 로딩  

---

## ⚡ B. 성능(Performance)
- [ ] Redis 캐싱 기본 설정  
- [ ] Refresh Token 저장(Allowlist/Blacklist)  
- [ ] JPA Fetch 전략 기본 튜닝  

---

## 🛡 C. 안정성(Reliability)
- [ ] GlobalExceptionHandler  
- [ ] ErrorCode Enum  
- [ ] Validation 예외 처리  
- [ ] 공통 에러 응답 구조(ApiErrorResponse)

---

# 📌 SUMMARY — 최종 필수 구성

**이 항목들만 완성하면 어떤 서비스든 바로 올릴 수 있는 “범용 백엔드 기반 구조”가 된다.**

✔ 인증  
✔ 사용자 관리  
✔ Push 알림  
✔ 파일 업로드  
✔ 보안  
✔ 캐싱/RefreshToken  
✔ 예외 처리  

백엔드 공통 핵심의 완성형 셋업.

---

## Schedule AI fallback

일정 문구는 먼저 규칙 파서로 분석하고, 날짜·시간·도착지 중 누락된 값이 있을 때만 Groq를 호출한다.
전화번호와 이메일은 Groq 전송 전에 마스킹된다.

```properties
GROQ_ENABLED=true
GROQ_API_KEY=발급받은_API_KEY
GROQ_MODEL=openai/gpt-oss-20b
```

API 키가 없거나 Groq 호출이 실패하면 규칙 분석 결과를 유지하며 사용자 확인이 필요한 필드를 반환한다.
