package com.noLate.legal.controller

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HomePageController {

    @GetMapping("/robots.txt", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun getRobotsTxt(): String =
        """
        User-agent: *
        Allow: /
        """.trimIndent()

    @GetMapping("/", produces = [MediaType.TEXT_HTML_VALUE])
    fun getHomePage(): String =
        """
        <!doctype html>
        <html lang="ko">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1" />
          <meta name="application-name" content="NoLate" />
          <meta name="description" content="NoLate는 일정, 이동 경로와 출발 시각을 함께 관리해 약속에 늦지 않도록 돕는 모바일 일정 관리 서비스입니다." />
          <meta property="og:site_name" content="NoLate" />
          <meta property="og:title" content="NoLate - 일정과 이동을 함께 준비하는 출발 도우미" />
          <meta property="og:description" content="일정 등록, 경로 탐색, 출발 알림, 캘린더 가져오기와 일정 공유를 제공하는 모바일 생산성 앱입니다." />
          <title>NoLate | 서비스 소개</title>
          <style>
            :root {
              color-scheme: light;
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
              --ink: #11141a;
              --muted: #5f6775;
              --line: #e4e8ef;
              --surface: #ffffff;
              --soft: #f5f7fb;
              --blue: #1677ff;
              --naver: #03c75a;
            }
            * { box-sizing: border-box; }
            html { scroll-behavior: smooth; }
            body {
              margin: 0;
              background: var(--soft);
              color: var(--ink);
              line-height: 1.65;
            }
            a { color: inherit; }
            main, footer, nav {
              width: min(100% - 40px, 1040px);
              margin: 0 auto;
            }
            header {
              position: sticky;
              top: 0;
              z-index: 10;
              border-bottom: 1px solid rgba(17, 20, 26, 0.08);
              background: rgba(255, 255, 255, 0.92);
              backdrop-filter: blur(18px);
            }
            nav {
              min-height: 68px;
              display: flex;
              align-items: center;
              justify-content: space-between;
              gap: 20px;
            }
            .brand {
              font-size: 22px;
              font-weight: 950;
              letter-spacing: -0.03em;
              text-decoration: none;
            }
            .nav-links { display: flex; gap: 18px; flex-wrap: wrap; }
            .nav-links a { font-size: 13px; font-weight: 800; }
            .hero {
              display: grid;
              grid-template-columns: minmax(0, 1.25fr) minmax(280px, 0.75fr);
              gap: 42px;
              align-items: center;
              padding: 86px 0 70px;
            }
            .eyebrow, .section-label {
              color: var(--blue);
              font-size: 13px;
              font-weight: 900;
              letter-spacing: 0.05em;
            }
            h1 {
              max-width: 720px;
              margin: 12px 0 18px;
              font-size: clamp(42px, 7vw, 72px);
              line-height: 1.08;
              letter-spacing: -0.055em;
            }
            .lead {
              max-width: 680px;
              margin: 0;
              color: var(--muted);
              font-size: 18px;
              font-weight: 650;
            }
            .status-card, .card, .panel, .phone {
              border: 1px solid var(--line);
              background: var(--surface);
              box-shadow: 0 18px 50px rgba(24, 34, 52, 0.07);
            }
            .status-card { padding: 26px; border-radius: 26px; }
            .status-card h2 { margin: 0 0 14px; font-size: 19px; }
            .status-list { display: grid; gap: 12px; margin: 0; }
            .status-row { display: grid; grid-template-columns: 88px 1fr; gap: 12px; }
            .status-row dt { color: var(--muted); font-size: 13px; font-weight: 800; }
            .status-row dd { margin: 0; font-size: 14px; font-weight: 750; }
            section.block { padding: 74px 0; }
            .section-title {
              max-width: 720px;
              margin: 8px 0 12px;
              font-size: clamp(30px, 5vw, 46px);
              line-height: 1.18;
              letter-spacing: -0.04em;
            }
            .section-copy { max-width: 760px; margin: 0; color: var(--muted); font-size: 16px; }
            .grid {
              display: grid;
              grid-template-columns: repeat(3, minmax(0, 1fr));
              gap: 16px;
              margin-top: 30px;
            }
            .card { min-height: 210px; padding: 24px; border-radius: 22px; }
            .card-index {
              display: inline-grid;
              width: 34px;
              height: 34px;
              place-items: center;
              border-radius: 11px;
              background: #eaf2ff;
              color: var(--blue);
              font-size: 13px;
              font-weight: 900;
            }
            .card h3 { margin: 18px 0 8px; font-size: 19px; letter-spacing: -0.02em; }
            .card p { margin: 0; color: var(--muted); font-size: 14px; }
            .screen-grid {
              display: grid;
              grid-template-columns: repeat(2, minmax(0, 1fr));
              gap: 22px;
              margin-top: 32px;
            }
            .phone { padding: 18px; border-radius: 30px; }
            .phone-head { display: flex; justify-content: space-between; font-size: 12px; font-weight: 850; }
            .mock-calendar { margin: 22px 0; display: grid; grid-template-columns: repeat(7, 1fr); gap: 7px; }
            .mock-calendar span { aspect-ratio: 1; display: grid; place-items: center; border-radius: 50%; font-size: 11px; }
            .mock-calendar .active { background: var(--ink); color: #fff; }
            .mock-event, .mock-route {
              border-radius: 16px;
              background: var(--soft);
              padding: 16px;
            }
            .mock-event { border-left: 5px solid #ff5b4d; }
            .mock-event strong, .mock-route strong { display: block; margin-bottom: 6px; }
            .mock-event span, .mock-route span { color: var(--muted); font-size: 13px; }
            .route-line { height: 8px; margin: 16px 0; border-radius: 999px; background: linear-gradient(90deg, #5b95ff 0 18%, #7d9500 18% 70%, #a46bc0 70% 100%); }
            .screen-caption { margin: 14px 4px 2px; color: var(--muted); font-size: 13px; }
            .panel { padding: 32px; border-radius: 26px; }
            .split { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-top: 30px; }
            .panel h3 { margin: 0 0 12px; font-size: 23px; }
            .panel p, .panel li { color: var(--muted); }
            .panel ul { margin: 14px 0 0; padding-left: 20px; }
            .panel li + li { margin-top: 8px; }
            .naver-panel { border-top: 5px solid var(--naver); }
            .notice-panel { border-top: 5px solid var(--ink); }
            .url-list { display: grid; gap: 12px; margin-top: 20px; }
            .url-row {
              display: grid;
              grid-template-columns: 155px 1fr;
              gap: 14px;
              padding-top: 12px;
              border-top: 1px solid var(--line);
            }
            .url-row strong { font-size: 13px; }
            .url-row a { color: var(--blue); overflow-wrap: anywhere; }
            .data-use { margin-top: 20px; }
            footer {
              padding: 34px 0 50px;
              border-top: 1px solid var(--line);
              color: #69707d;
              font-size: 13px;
            }
            footer div { display: flex; gap: 16px; flex-wrap: wrap; margin-bottom: 8px; }
            @media (max-width: 780px) {
              .hero, .split { grid-template-columns: 1fr; }
              .grid { grid-template-columns: 1fr; }
              .screen-grid { grid-template-columns: 1fr; }
              .hero { padding-top: 58px; }
              .nav-links a[href="#screens"], .nav-links a[href="#service-info"] { display: none; }
              .url-row { grid-template-columns: 1fr; gap: 4px; }
            }
          </style>
        </head>
        <body>
          <header>
            <nav aria-label="주요 메뉴">
              <a class="brand" href="/">NoLate</a>
              <div class="nav-links">
                <a href="#features">주요 기능</a>
                <a href="#screens">화면 예시</a>
                <a href="#naver-login">네이버 로그인</a>
                <a href="#service-info">서비스 정보</a>
                <a href="/legal/privacy-policy">개인정보처리방침</a>
              </div>
            </nav>
          </header>
          <main>
            <section class="hero">
              <div>
                <div class="eyebrow">MOBILE SCHEDULE &amp; DEPARTURE ASSISTANT</div>
                <h1>늦지 않도록,<br />일정과 이동을 함께 준비합니다.</h1>
                <h2>App Purpose / 앱의 목적</h2>
                <p class="lead">
                  NoLate는 일정의 시간과 장소, 이동 경로와 예상 소요시간을 연결해 적절한 출발 시점을 준비하도록 돕는 모바일 일정 관리 서비스입니다.
                  사용자는 일정을 직접 만들거나 캘린더에서 선택해 가져오고, 대중교통·자동차 경로를 확인하고, 출발 알림을 받을 수 있습니다.
                </p>
              </div>
              <aside class="status-card" aria-label="서비스 개요">
                <h2>검수용 서비스 개요</h2>
                <dl class="status-list">
                  <div class="status-row"><dt>서비스명</dt><dd>NoLate</dd></div>
                  <div class="status-row"><dt>업종·유형</dt><dd>모바일 일정 관리·이동 지원 생산성 서비스</dd></div>
                  <div class="status-row"><dt>이용 대상</dt><dd>개인 일정과 이동 계획을 관리하려는 일반 사용자</dd></div>
                  <div class="status-row"><dt>제공 환경</dt><dd>iOS·Android 모바일 애플리케이션</dd></div>
                  <div class="status-row"><dt>현재 상태</dt><dd>모바일 앱 정식 출시 준비 중, 공개 소개·정책 페이지 운영 중</dd></div>
                  <div class="status-row"><dt>거래 여부</dt><dd>예약·상품 매매·중개·결제 기능 없음</dd></div>
                </dl>
              </aside>
            </section>

            <section class="block" id="features" aria-labelledby="features-title">
              <div class="section-label">MENU-BY-MENU FEATURES</div>
              <h2 class="section-title" id="features-title">앱에서 제공하는 메뉴와 기능</h2>
              <p class="section-copy">아래 기능은 로그인한 사용자의 개인 일정 관리를 위해 제공되며, 상품 판매나 예약 중개를 목적으로 하지 않습니다.</p>
              <div class="grid">
                <article class="card">
                  <span class="card-index">01</span>
                  <h3>일정 홈·캘린더</h3>
                  <p>월·일 단위로 일정을 확인하고 제목, 시작·종료 시각, 카테고리와 장소를 등록·수정·삭제합니다.</p>
                </article>
                <article class="card">
                  <span class="card-index">02</span>
                  <h3>장소·이동 경로</h3>
                  <p>출발지와 도착지를 검색하고 대중교통·자동차 경로의 예상 시간, 환승, 요금과 이동 단계를 비교합니다.</p>
                </article>
                <article class="card">
                  <span class="card-index">03</span>
                  <h3>출발 알림</h3>
                  <p>일정 시각과 선택한 이동 경로를 기준으로 준비 알림과 출발 시점 알림을 제공합니다. 알림은 사용자가 직접 켜고 끌 수 있습니다.</p>
                </article>
                <article class="card">
                  <span class="card-index">04</span>
                  <h3>캘린더 가져오기</h3>
                  <p>기기 또는 Google Calendar에서 후보 일정을 조회하고, 사용자가 선택한 일정만 NoLate 일정으로 가져옵니다.</p>
                </article>
                <article class="card">
                  <span class="card-index">05</span>
                  <h3>빠른 일정 입력</h3>
                  <p>사용자가 입력한 문장이나 선택한 이미지에서 날짜·시간·장소 후보를 추출해 확인 가능한 일정 초안을 만듭니다.</p>
                </article>
                <article class="card">
                  <span class="card-index">06</span>
                  <h3>일정 공유·프로필</h3>
                  <p>선택한 일정만 다른 NoLate 회원과 공유하고, 프로필에서 계정 정보, 로그인 방식, 캘린더 연결과 동의 내역을 관리합니다.</p>
                </article>
              </div>
            </section>

            <section class="block" id="screens" aria-labelledby="screens-title">
              <div class="section-label">REPRESENTATIVE SCREENS</div>
              <h2 class="section-title" id="screens-title">대표 화면 예시</h2>
              <p class="section-copy">실제 앱에서는 아래 구조로 일정과 이동 정보를 확인합니다. 검수 신청에는 별도 서비스 소개 자료로 실제 기기 화면도 함께 제출합니다.</p>
              <div class="screen-grid">
                <article>
                  <div class="phone" aria-label="일정 캘린더 화면 예시">
                    <div class="phone-head"><span>NoLate 일정</span><span>7월 2026</span></div>
                    <div class="mock-calendar" aria-hidden="true">
                      <span>5</span><span>6</span><span>7</span><span>8</span><span>9</span><span>10</span><span>11</span>
                      <span>12</span><span>13</span><span>14</span><span class="active">15</span><span>16</span><span>17</span><span>18</span>
                    </div>
                    <div class="mock-event"><strong>오후 3시 프로젝트 미팅</strong><span>광화문 · 대중교통 42분 · 출발 알림 켜짐</span></div>
                  </div>
                  <p class="screen-caption">일정 홈: 날짜별 일정, 장소, 선택 경로와 알림 상태를 한 화면에서 확인</p>
                </article>
                <article>
                  <div class="phone" aria-label="대중교통 경로 화면 예시">
                    <div class="phone-head"><span>경로 상세</span><span>최적 42분</span></div>
                    <div class="mock-route" style="margin-top: 22px"><strong>서울역 → 광화문</strong><span>대중교통 · 환승 1회 · 예상 요금 표시</span><div class="route-line"></div><span>도보 6분 · 지하철 28분 · 도보 8분</span></div>
                    <div class="mock-route" style="margin-top: 12px"><strong>일정에 경로 저장</strong><span>저장한 예상 이동시간을 출발 알림 계산에 반영</span></div>
                  </div>
                  <p class="screen-caption">경로 상세: 출발·도착지, 이동 단계, 소요시간과 환승 정보를 확인한 뒤 일정에 저장</p>
                </article>
              </div>
            </section>

            <section class="block" id="naver-login" aria-labelledby="naver-title">
              <div class="section-label">NAVER LOGIN USE</div>
              <h2 class="section-title" id="naver-title">네이버 로그인 적용 방식</h2>
              <div class="split">
                <article class="panel naver-panel">
                  <h3>로그인·자동 회원가입</h3>
                  <p>사용자가 로그인 화면에서 네이버를 선택하고 정보 제공에 동의하면 별도 비밀번호 입력 없이 NoLate 계정이 생성되거나 기존 계정으로 로그인됩니다.</p>
                  <ul>
                    <li>요청 정보: 네이버 회원 식별자, 회원 이름, 이메일 주소(사용자가 제공에 동의한 경우)</li>
                    <li>사용 목적: 계정 식별, 로그인 처리, 앱 내 프로필의 이름·이메일 표시</li>
                    <li>로그아웃 시 NoLate 세션과 네이버 SDK 세션 정리</li>
                    <li>회원 탈퇴 시 NoLate 회원 데이터 삭제 후 네이버 연결 해제</li>
                  </ul>
                </article>
                <article class="panel notice-panel">
                  <h3>서비스 성격 소명</h3>
                  <p>NoLate는 사용자의 개인 일정을 관리하는 도구입니다. 앱에서 사용자가 “병원 예약”, “미팅”, “촬영 일정” 같은 제목을 입력할 수 있으나 이는 개인 캘린더 기록이며 NoLate가 예약을 접수·중개하거나 상품을 판매하는 기능이 아닙니다.</p>
                  <ul>
                    <li>예약·청약·매매·상품 중개 기능 없음</li>
                    <li>앱 내 결제 및 거래 당사자 간 대금 정산 기능 없음</li>
                    <li>불법·성인·사행성·가상자산 등 특수 업종 콘텐츠 없음</li>
                  </ul>
                </article>
              </div>
            </section>

            <section class="block" id="service-info" aria-labelledby="service-info-title">
              <div class="section-label">PUBLIC SERVICE INFORMATION</div>
              <h2 class="section-title" id="service-info-title">공개 URL과 출시 상태</h2>
              <div class="panel">
                <p>모바일 애플리케이션은 iOS·Android 정식 출시를 준비하고 있습니다. 현재 아래 공개 URL에서 서비스 목적, 기능, 개인정보 처리와 이용 조건을 로그인 없이 확인할 수 있습니다.</p>
                <div class="url-list">
                  <div class="url-row"><strong>서비스 예정·소개 URL</strong><a href="https://nolate.jinuk.dev/">https://nolate.jinuk.dev/</a></div>
                  <div class="url-row"><strong>개인정보처리방침</strong><a href="/legal/privacy-policy">https://nolate.jinuk.dev/legal/privacy-policy</a></div>
                  <div class="url-row"><strong>서비스 이용약관</strong><a href="/legal/terms-of-service">https://nolate.jinuk.dev/legal/terms-of-service</a></div>
                  <div class="url-row"><strong>서비스 문의</strong><a href="mailto:support@nolate.jinuk.dev">support@nolate.jinuk.dev</a></div>
                </div>
              </div>

              <div class="panel data-use" aria-labelledby="google-data-title">
                <h3 id="google-data-title">How NoLate Uses Google Calendar Data</h3>
                <p lang="en">
                  NoLate requests read-only access to calendar lists and upcoming events only after the user consents to connect Google Calendar.
                  This data is used solely to show import candidates and let the user select which events to manage in NoLate.
                </p>
                <p>NoLate는 사용자가 Google Calendar 연동에 동의한 경우에만 캘린더 목록과 다가오는 일정을 읽기 전용으로 조회합니다. 사용자가 직접 선택한 일정만 NoLate 일정으로 저장하며, Google 사용자 데이터를 광고·판매·신용평가·데이터 중개 또는 AI 모델 학습에 사용하지 않습니다.</p>
              </div>
            </section>
          </main>
          <footer>
            <div>
              <strong>NoLate</strong>
              <a href="/legal/privacy-policy">개인정보처리방침</a>
              <a href="/legal/terms-of-service">서비스 이용약관</a>
            </div>
            <span>서비스 문의: support@nolate.jinuk.dev · 개인정보 문의: privacy@nolate.jinuk.dev</span>
          </footer>
        </body>
        </html>
        """.trimIndent()
}
