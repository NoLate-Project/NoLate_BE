package com.noLate.legal.controller

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HomePageController {

    @GetMapping("/", produces = [MediaType.TEXT_HTML_VALUE])
    fun getHomePage(): String =
        """
        <!doctype html>
        <html lang="ko">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1" />
          <meta name="application-name" content="NoLate" />
          <meta name="description" content="NoLate is a mobile schedule and departure assistant that helps users manage appointments, routes, and departure times so they can arrive on time." />
          <meta property="og:site_name" content="NoLate" />
          <meta property="og:title" content="NoLate" />
          <meta property="og:description" content="NoLate helps users manage schedules, travel routes, and departure times so they can arrive on time." />
          <title>NoLate</title>
          <style>
            :root {
              color-scheme: light dark;
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
            }
            * { box-sizing: border-box; }
            body {
              margin: 0;
              background: #f6f7fb;
              color: #15171c;
              line-height: 1.65;
            }
            a { color: inherit; }
            main, footer {
              width: min(100% - 40px, 920px);
              margin: 0 auto;
            }
            header {
              border-bottom: 1px solid rgba(0, 0, 0, 0.08);
              background: rgba(255, 255, 255, 0.82);
            }
            nav {
              width: min(100% - 40px, 920px);
              min-height: 68px;
              margin: 0 auto;
              display: flex;
              align-items: center;
              justify-content: space-between;
              gap: 20px;
            }
            .brand {
              font-size: 21px;
              font-weight: 900;
              text-decoration: none;
            }
            .nav-links { display: flex; gap: 18px; flex-wrap: wrap; }
            .nav-links a { font-size: 13px; font-weight: 750; }
            .hero { padding: 88px 0 56px; }
            .eyebrow {
              color: #5f6673;
              font-size: 13px;
              font-weight: 850;
              letter-spacing: 0.04em;
            }
            h1 {
              max-width: 720px;
              margin: 10px 0 18px;
              font-size: clamp(40px, 8vw, 68px);
              line-height: 1.08;
              letter-spacing: -0.04em;
            }
            .lead {
              max-width: 690px;
              margin: 0;
              color: #555d6b;
              font-size: 18px;
              font-weight: 600;
            }
            .lead + .lead { margin-top: 12px; font-size: 16px; }
            .grid {
              display: grid;
              grid-template-columns: repeat(3, minmax(0, 1fr));
              gap: 16px;
              padding: 16px 0 58px;
            }
            .card, .data-use {
              border: 1px solid rgba(0, 0, 0, 0.08);
              border-radius: 22px;
              background: #fff;
            }
            .card { padding: 24px; }
            .card h2 { margin: 0 0 8px; font-size: 18px; }
            .card p { margin: 0; color: #5f6673; font-size: 14px; }
            .data-use { padding: 30px; margin-bottom: 70px; }
            .data-use h2 { margin: 0 0 12px; font-size: 25px; }
            .data-use p, .data-use li { color: #555d6b; }
            .data-use ul { margin: 12px 0 0; padding-left: 22px; }
            .data-use li + li { margin-top: 8px; }
            footer {
              padding: 28px 0 44px;
              border-top: 1px solid rgba(0, 0, 0, 0.08);
              color: #69707d;
              font-size: 13px;
            }
            footer div { display: flex; gap: 16px; flex-wrap: wrap; margin-bottom: 8px; }
            @media (max-width: 720px) {
              .grid { grid-template-columns: 1fr; }
              .hero { padding-top: 62px; }
              .nav-links { gap: 12px; }
            }
            @media (prefers-color-scheme: dark) {
              body { background: #0f1115; color: #f5f6f8; }
              header { background: rgba(15, 17, 21, 0.88); border-color: rgba(255, 255, 255, 0.09); }
              .eyebrow, .lead, .card p, .data-use p, .data-use li, footer { color: #adb3bd; }
              .card, .data-use { background: #17191f; border-color: rgba(255, 255, 255, 0.09); }
              footer { border-color: rgba(255, 255, 255, 0.09); }
            }
          </style>
        </head>
        <body>
          <header>
            <nav aria-label="주요 메뉴">
              <a class="brand" href="/">NoLate</a>
              <div class="nav-links">
                <a href="/legal/privacy-policy">개인정보처리방침</a>
                <a href="/legal/terms-of-service">서비스 이용약관</a>
              </div>
            </nav>
          </header>
          <main>
            <section class="hero">
              <div class="eyebrow">APP NAME / 앱 이름: NoLate</div>
              <h1>NoLate</h1>
              <h2>App Purpose / 앱의 목적</h2>
              <p class="lead" lang="en">
                NoLate is a mobile schedule and departure assistant that helps users manage appointments,
                travel routes, and departure times so they can leave at the right time and arrive on time.
                Users can create schedules, selectively import calendar events, review travel routes,
                receive departure reminders, and share only the schedules they choose.
              </p>
              <p class="lead" lang="ko">
                NoLate는 일정, 장소, 이동 시간을 한곳에서 관리하고 적절한 출발 시점을 알려 주는 모바일 앱입니다.
                사용자는 일정을 직접 만들거나 캘린더에서 선택해 가져오고, 경로를 확인하고, 필요한 일정만 다른 사람과 공유할 수 있습니다.
              </p>
            </section>

            <section class="grid" aria-label="NoLate Main Features / 주요 기능">
              <article class="card">
                <h2>Schedules &amp; Departure Reminders</h2>
                <p>일정 시간과 목적지까지의 이동 시간을 바탕으로 출발 준비와 출발 시점을 알려 줍니다.</p>
              </article>
              <article class="card">
                <h2>Travel Route Guidance</h2>
                <p>등록한 장소를 기준으로 대중교통과 이동 경로를 비교해 일정에 맞는 이동 계획을 세울 수 있습니다.</p>
              </article>
              <article class="card">
                <h2>Selective Calendar Import</h2>
                <p>사용자가 선택한 외부 캘린더 일정을 NoLate 일정으로 가져와 한곳에서 관리할 수 있습니다.</p>
              </article>
            </section>

            <section class="data-use" aria-labelledby="google-data-title">
              <h2 id="google-data-title">How NoLate Uses Google Calendar Data</h2>
              <p lang="en">
                NoLate requests read-only access to calendar lists and upcoming events only after the user
                consents to connect Google Calendar. This data is used solely to show import candidates and
                let the user select which events to manage in NoLate.
              </p>
              <p>
                NoLate는 사용자가 Google Calendar 연동에 동의한 경우에만 캘린더 목록과 다가오는 일정을 읽기 전용으로 조회합니다.
                이 정보는 가져올 일정 후보를 보여 주고, 사용자가 선택한 일정을 NoLate에서 관리하도록 제공하는 데 사용됩니다.
              </p>
              <ul>
                <li>Google Calendar 원본 전체를 서버에 일괄 저장하지 않으며, 사용자가 직접 선택한 일정만 NoLate 일정으로 저장합니다.</li>
                <li>Google 접근 토큰은 기기의 보안 저장소에 보관하며 현재 NoLate 서버에는 저장하지 않습니다.</li>
                <li>Google 사용자 데이터를 판매하거나 광고, 신용 평가, 데이터 중개 또는 AI 모델 학습에 사용하지 않습니다.</li>
                <li>연동은 Google 계정 보안 설정이나 기기에서 언제든지 철회할 수 있습니다.</li>
              </ul>
              <p>
                수집 항목, 이용 목적, 보유 기간, 제3자 제공 및 삭제 방법의 자세한 내용은
                <a href="/legal/privacy-policy"><strong>NoLate Privacy Policy / 개인정보처리방침</strong></a>에서 확인할 수 있습니다.
              </p>
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
