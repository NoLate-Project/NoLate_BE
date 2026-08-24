package com.noLate.global.web

import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Browser fallback for HTTPS share links.
 *
 * Universal Links normally route `/share/{token}` straight into the installed app. Browsers and
 * in-app browsers can decline that hand-off, so this endpoint renders an explicit app-open action
 * instead of exposing the API authentication envelope. It deliberately does not inspect or accept
 * the invitation: acceptance remains an authenticated in-app POST.
 */
@RestController
class ShareInvitationLandingController {
    @GetMapping("/share/{token}", produces = [MediaType.TEXT_HTML_VALUE])
    fun landing(@PathVariable token: String): ResponseEntity<String> {
        val normalizedToken = token.takeIf(SHARE_TOKEN_PATTERN::matches)
            ?: return html(HttpStatus.NOT_FOUND, invalidInvitationPage())

        return html(
            HttpStatus.OK,
            invitationPage("nolate://share/${urlEncode(normalizedToken)}"),
        )
    }

    private fun invitationPage(appUrl: String): String = layout(
        title = "공유 초대",
        body =
            """
            <div class="app-icon" aria-hidden="true">N</div>
            <p class="eyebrow">SHARE INVITATION</p>
            <h1>NoLate 공유 초대가 도착했어요.</h1>
            <p class="lead">
              NoLate 앱에서 초대를 확인한 뒤 참여할 수 있어요.
              버튼을 눌러 앱을 열어 주세요.
            </p>
            <a class="button" href="$appUrl">NoLate 앱에서 열기</a>
            <div class="notice">
              <strong>앱이 열리지 않나요?</strong>
              <span>웹에서는 초대가 수락되지 않아요. NoLate가 설치된 기기에서 링크를 다시 열거나 최신 버전으로 업데이트해 주세요.</span>
            </div>
            """.trimIndent(),
    )

    private fun invalidInvitationPage(): String = layout(
        title = "유효하지 않은 공유 링크",
        body =
            """
            <div class="app-icon muted" aria-hidden="true">N</div>
            <p class="eyebrow">INVALID LINK</p>
            <h1>공유 링크를 확인할 수 없어요.</h1>
            <p class="lead">초대를 보낸 사람에게 새 링크를 요청해 주세요.</p>
            """.trimIndent(),
    )

    private fun html(status: HttpStatus, body: String): ResponseEntity<String> =
        ResponseEntity.status(status)
            .contentType(MediaType("text", "html", StandardCharsets.UTF_8))
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.PRAGMA, "no-cache")
            .header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
            .header("Referrer-Policy", "no-referrer")
            .header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
            .header("X-Frame-Options", "DENY")
            .header("X-Content-Type-Options", "nosniff")
            .header("X-Robots-Tag", "noindex, nofollow, noarchive, nosnippet")
            .body(body)

    private fun layout(title: String, body: String): String =
        """
        <!doctype html>
        <html lang="ko">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
          <meta name="robots" content="noindex,nofollow,noarchive,nosnippet" />
          <meta name="referrer" content="no-referrer" />
          <meta name="theme-color" content="#f5f8ff" />
          <title>$title | NoLate</title>
          <style>
            :root {
              color-scheme: light;
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
              --ink: #111827; --muted: #667085; --blue: #246bfe;
              --blue-soft: #eaf2ff; --line: #e4e9f2; --surface: #ffffff;
            }
            * { box-sizing: border-box; }
            body {
              min-height: 100vh; margin: 0; padding: max(24px, env(safe-area-inset-top)) 20px
                max(24px, env(safe-area-inset-bottom)); display: grid; place-items: center;
              background: radial-gradient(circle at 50% 0%, #ffffff 0, #f5f8ff 48%, #edf3ff 100%);
              color: var(--ink);
            }
            main {
              width: min(100%, 440px); padding: 38px 28px 30px; text-align: center;
              border: 1px solid rgba(36, 107, 254, 0.10); border-radius: 30px;
              background: rgba(255, 255, 255, 0.92); box-shadow: 0 24px 70px rgba(37, 64, 120, 0.13);
            }
            .app-icon {
              width: 76px; height: 76px; margin: 0 auto 24px; display: grid; place-items: center;
              border-radius: 23px; background: linear-gradient(145deg, #2b8cff, #1258ed);
              color: #fff; font-size: 36px; font-weight: 950; box-shadow: 0 14px 30px rgba(36, 107, 254, 0.26);
            }
            .app-icon.muted { background: #98a2b3; box-shadow: none; }
            .eyebrow { margin: 0 0 10px; color: var(--blue); font-size: 12px; font-weight: 900; letter-spacing: .11em; }
            h1 { margin: 0; font-size: clamp(27px, 8vw, 34px); line-height: 1.22; letter-spacing: -.045em; }
            .lead { margin: 18px 0 28px; color: var(--muted); font-size: 16px; font-weight: 620; line-height: 1.65; }
            .button {
              min-height: 58px; display: grid; place-items: center; border-radius: 18px;
              background: var(--blue); color: #fff; font-size: 17px; font-weight: 850; text-decoration: none;
              box-shadow: 0 12px 24px rgba(36, 107, 254, 0.22);
            }
            .button:focus-visible { outline: 4px solid rgba(36, 107, 254, .25); outline-offset: 3px; }
            .notice {
              margin-top: 22px; padding: 16px; display: grid; gap: 5px; text-align: left;
              border: 1px solid var(--line); border-radius: 16px; background: #f8faff;
            }
            .notice strong { font-size: 14px; }
            .notice span { color: var(--muted); font-size: 13px; line-height: 1.55; }
            @media (prefers-reduced-motion: reduce) { * { scroll-behavior: auto !important; } }
          </style>
        </head>
        <body><main>$body</main></body>
        </html>
        """.trimIndent()

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private companion object {
        val SHARE_TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{16,512}$")
        const val CONTENT_SECURITY_POLICY =
            "default-src 'none'; style-src 'unsafe-inline'; img-src 'none'; font-src 'none'; " +
                "script-src 'none'; connect-src 'none'; media-src 'none'; object-src 'none'; " +
                "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
    }
}
