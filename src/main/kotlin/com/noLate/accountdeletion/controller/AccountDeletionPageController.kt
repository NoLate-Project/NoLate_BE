package com.noLate.accountdeletion.controller

import com.noLate.accountdeletion.application.AccountDeletionCoordinator
import com.noLate.accountdeletion.application.AccountDeletionIdentityVerificationPort
import com.noLate.accountdeletion.application.AccountDeletionProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * Login-free Google Play account-deletion surface.
 *
 * The page deliberately uses server-rendered forms, no third-party assets and no URL/query
 * secrets. POST requests must originate from the configured public origin, while the application
 * service still requires a delivered verification secret and a second single-use deletion grant.
 */
@RestController
class AccountDeletionPageController(
    private val coordinator: AccountDeletionCoordinator,
    private val properties: AccountDeletionProperties,
    private val verificationPort: AccountDeletionIdentityVerificationPort,
) {
    @GetMapping("/account-deletion", produces = [MediaType.TEXT_HTML_VALUE])
    fun page(): ResponseEntity<String> =
        html(HttpStatus.OK, landingPage())

    @PostMapping(
        "/account-deletion/requests",
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE],
        produces = [MediaType.TEXT_HTML_VALUE],
    )
    fun requestDeletion(
        @RequestParam(required = false) email: String?,
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        if (!isTrustedFormPost(request)) return originRejected()
        if (!automaticFlowAvailable()) return automaticFlowUnavailable()

        val receipt = coordinator.requestDeletion(
            submittedEmail = email,
            requesterAddress = request.remoteAddr,
        )
        return html(
            HttpStatus.ACCEPTED,
            layout(
                title = "삭제 요청 접수",
                body =
                    """
                    <p class="eyebrow">REQUEST RECEIVED</p>
                    <h1>요청을 접수했습니다.</h1>
                    <p class="lead">
                      입력한 정보로 본인확인이 가능한 경우에만 등록된 인증 수단으로 확인 코드를 보냅니다.
                      계정 존재 여부, 로그인 방식 또는 발송 여부는 이 화면에서 공개하지 않습니다.
                    </p>
                    <div class="notice">
                      <strong>요청 번호</strong>
                      <code>${escape(receipt.requestId)}</code>
                      <span>문의와 본인확인에 필요하므로 이 브라우저에만 안전하게 보관하세요.</span>
                    </div>
                    <form method="post" action="/account-deletion/verify" autocomplete="off">
                      <input type="hidden" name="requestId" value="${escape(receipt.requestId)}" />
                      <label for="verificationCode">확인 코드</label>
                      <input id="verificationCode" name="verificationCode" minlength="8" maxlength="32"
                             inputmode="text" autocomplete="one-time-code" required />
                      <button type="submit">본인확인 계속</button>
                    </form>
                    <p class="help">
                      코드가 오지 않거나 SNS 로그인 계정의 이메일을 사용할 수 없다면
                      <a href="mailto:${escape(properties.supportEmail)}">${escape(properties.supportEmail)}</a>로 문의하세요.
                    </p>
                    """.trimIndent(),
            ),
        )
    }

    @PostMapping(
        "/account-deletion/verify",
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE],
        produces = [MediaType.TEXT_HTML_VALUE],
    )
    fun verify(
        @RequestParam(required = false) requestId: String?,
        @RequestParam(required = false) verificationCode: String?,
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        if (!isTrustedFormPost(request)) return originRejected()
        if (!automaticFlowAvailable()) return automaticFlowUnavailable()

        val result = coordinator.verify(requestId, verificationCode)
        val deletionGrant = result.deletionGrant
        if (deletionGrant == null) {
            return html(
                HttpStatus.BAD_REQUEST,
                layout(
                    title = "본인확인 필요",
                    body =
                        """
                        <p class="eyebrow">VERIFICATION REQUIRED</p>
                        <h1>본인확인을 완료하지 못했습니다.</h1>
                        <p class="lead">
                          코드가 만료·불일치했거나 현재 지원하지 않는 로그인 방식일 수 있습니다.
                          계정 존재 여부와 구체적인 실패 원인은 공개하지 않습니다.
                        </p>
                        <a class="button secondary" href="/account-deletion">새 요청 시작</a>
                        """.trimIndent(),
                ),
            )
        }

        return html(
            HttpStatus.OK,
            layout(
                title = "최종 삭제 확인",
                body =
                    """
                    <p class="eyebrow warning-text">FINAL CONFIRMATION</p>
                    <h1>계정 삭제는 되돌릴 수 없습니다.</h1>
                    <p class="lead">
                      최종 확인하면 로그인 세션이 종료되고 개인 일정, 카테고리, 즐겨찾기,
                      최근 장소, 프로필, 설정, 동의 기록, 알림·이동 상태와 푸시 토큰이 삭제됩니다.
                      회원 행은 참조 안정성을 위해 남지만 이름·이메일·비밀번호·SNS 식별자는 제거되고 로그인은 차단됩니다.
                    </p>
                    <div class="warning">
                      활성 공유 캘린더의 소유자인 경우 자동 삭제가 중단될 수 있습니다.
                      먼저 소유권 정책에 따른 조치가 필요하며, 이 경우 지원 안내가 표시됩니다.
                    </div>
                    <form method="post" action="/account-deletion/confirm" autocomplete="off">
                      <input type="hidden" name="requestId" value="${escape(result.requestId)}" />
                      <input type="hidden" name="deletionGrant" value="${escape(deletionGrant)}" />
                      <label class="check">
                        <input type="checkbox" name="acknowledged" value="true" required />
                        위 삭제 범위와 복구 불가 안내를 확인했습니다.
                      </label>
                      <button class="danger" type="submit">NoLate 계정 영구 삭제</button>
                    </form>
                    """.trimIndent(),
            ),
        )
    }

    @PostMapping(
        "/account-deletion/confirm",
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE],
        produces = [MediaType.TEXT_HTML_VALUE],
    )
    fun confirm(
        @RequestParam(required = false) requestId: String?,
        @RequestParam(required = false) deletionGrant: String?,
        @RequestParam(required = false) acknowledged: String?,
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        if (!isTrustedFormPost(request)) return originRejected()
        if (!automaticFlowAvailable()) return automaticFlowUnavailable()
        if (acknowledged != "true") {
            return html(
                HttpStatus.BAD_REQUEST,
                layout(
                    title = "최종 확인 필요",
                    body =
                        """
                        <p class="eyebrow">ACKNOWLEDGEMENT REQUIRED</p>
                        <h1>삭제 범위와 복구 불가 안내를 확인해야 합니다.</h1>
                        <p class="lead">
                          확인하지 않은 요청은 삭제 승인으로 처리하지 않았으며,
                          일회용 삭제 승인 정보도 소비하지 않았습니다.
                        </p>
                        <a class="button secondary" href="/account-deletion">삭제 페이지로 돌아가기</a>
                        """.trimIndent(),
                ),
            )
        }

        // The durable result remains available to the internal workflow, but the public response
        // must not distinguish a decoy, provider type, session change, owner state, or cleanup
        // failure. In particular, this page never claims that account deletion completed.
        coordinator.confirm(requestId, deletionGrant)
        return genericTerminalReceipt()
    }

    private fun landingPage(): String {
        val automaticallyAvailable = automaticFlowAvailable()
        val availability = if (automaticallyAvailable) {
            """
            <div class="notice success">
              외부 본인확인과 자동 삭제 요청을 이용할 수 있습니다.
            </div>
            """.trimIndent()
        } else {
            """
            <div class="notice">
              현재 외부 자동 본인확인은 준비 중이며 아래 지원 절차로만 삭제 요청을 접수합니다.
              이 페이지는 계정 존재 여부를 공개하지 않습니다.
            </div>
            """.trimIndent()
        }
        val requestAction = if (automaticallyAvailable) {
            """
            <form method="post" action="/account-deletion/requests" autocomplete="off">
              <label for="email">가입 이메일</label>
              <input id="email" name="email" type="email" maxlength="254"
                     inputmode="email" autocomplete="email" required />
              <button type="submit">계정 삭제 요청</button>
            </form>
            """.trimIndent()
        } else {
            supportAction()
        }
        return layout(
            title = "계정 및 데이터 삭제",
            body =
                """
                <p class="eyebrow">ACCOUNT &amp; DATA DELETION</p>
                <h1>${escape(properties.appName)} 계정과 데이터 삭제</h1>
                <p class="lead">
                  ${escape(properties.developerName)}가 제공하는 ${escape(properties.appName)} 계정은
                  앱에 로그인하지 않고도 이 페이지에서 삭제 절차를 시작할 수 있습니다.
                </p>
                $availability
                <section>
                  <h2>삭제 절차</h2>
                  <ol>
                    <li>가입에 사용한 이메일을 입력합니다.</li>
                    <li>지원되는 계정은 등록된 인증 수단으로 받은 단기 확인 코드를 입력합니다.</li>
                    <li>삭제 범위를 확인하고 한 번만 사용할 수 있는 최종 삭제를 승인합니다.</li>
                  </ol>
                </section>
                <section>
                  <h2>삭제되는 데이터</h2>
                  <p>
                    계정 인증정보, 개인 일정·카테고리, 프로필·설정, 즐겨찾기·최근 장소,
                    일정 공유 연결, 알림·이동 상태, 푸시 토큰과 서버 세션을 삭제하거나 재식별할 수 없도록 처리합니다.
                    다른 이용자가 만든 공유 캘린더의 일반 참여자는 탈퇴 처리됩니다.
                  </p>
                  <p>
                    활성 공유 캘린더 소유자는 다른 이용자 데이터 보호를 위해 자동 처리가 중단될 수 있으며
                    지원팀의 소유권 조치 후 삭제할 수 있습니다.
                  </p>
                </section>
                <section>
                  <h2>보관되는 데이터</h2>
                  <p>${escape(properties.retentionSummary)}</p>
                  <p>
                    삭제 요청 기록에는 입력한 이메일 원문 대신 단방향 키 기반 식별값과 처리 상태만 남기며,
                    ${properties.requestRecordRetention.toDays()}일 동안 보관하고 이후 정기 삭제합니다.
                    법령상 별도 보존 의무가 생기는 경우에는 해당 범위와 기간만 분리 보관합니다.
                  </p>
                </section>
                $requestAction
                <p class="help">
                  <a href="/legal/privacy-policy">개인정보처리방침</a>
                </p>
                """.trimIndent(),
        )
    }

    private fun automaticFlowUnavailable(): ResponseEntity<String> =
        html(
            HttpStatus.SERVICE_UNAVAILABLE,
            layout(
                title = "지원 절차 필요",
                body =
                    """
                    <p class="eyebrow">AUTOMATIC FLOW UNAVAILABLE</p>
                    <h1>외부 자동 삭제를 이용할 수 없습니다.</h1>
                    <p class="lead">
                      본인확인 제공자와 보유정책이 모두 준비되기 전에는 자동 삭제 요청을 접수하거나
                      삭제 완료로 표시하지 않습니다. 아래 지원 절차를 이용하세요.
                    </p>
                    ${supportAction()}
                    """.trimIndent(),
            ),
        )

    private fun genericTerminalReceipt(): ResponseEntity<String> =
        html(
            HttpStatus.ACCEPTED,
            layout(
                title = "삭제 요청 접수",
                body =
                    """
                    <p class="eyebrow">REQUEST RECEIVED</p>
                    <h1>삭제 요청을 접수했습니다.</h1>
                    <p class="lead">
                      보안을 위해 계정 존재 여부, 로그인 방식과 개별 처리 결과는 공개하지 않습니다.
                      자동 처리가 가능하면 안전하게 진행하며, 추가 본인확인이나 소유권 조치가
                      필요하다고 판단되면 아래 지원 절차를 이용하세요.
                    </p>
                    ${supportAction()}
                    <a class="button secondary" href="/">NoLate 홈으로</a>
                    """.trimIndent(),
            ),
        )

    private fun originRejected(): ResponseEntity<String> =
        html(
            HttpStatus.FORBIDDEN,
            layout(
                title = "요청 출처 확인 실패",
                body =
                    """
                    <p class="eyebrow">REQUEST BLOCKED</p>
                    <h1>안전한 요청 출처를 확인할 수 없습니다.</h1>
                    <p class="lead">공개 삭제 페이지를 새로 열고 다시 시도하세요.</p>
                    <a class="button secondary" href="/account-deletion">삭제 페이지 열기</a>
                    """.trimIndent(),
            ),
        )

    private fun isTrustedFormPost(request: HttpServletRequest): Boolean {
        if (request.getHeader("Sec-Fetch-Site")
                ?.equals("cross-site", ignoreCase = true) == true
        ) {
            return false
        }
        val source = request.getHeader(HttpHeaders.ORIGIN)
            ?: request.getHeader(HttpHeaders.REFERER)
            ?: return false
        return sameOrigin(source, properties.publicOrigin)
    }

    private fun sameOrigin(source: String, configuredOrigin: String): Boolean =
        runCatching {
            val sourceUri = URI(source)
            val configuredUri = URI(configuredOrigin)
            sourceUri.scheme.equals(configuredUri.scheme, ignoreCase = true) &&
                sourceUri.host.equals(configuredUri.host, ignoreCase = true) &&
                effectivePort(sourceUri) == effectivePort(configuredUri)
        }.getOrDefault(false)

    private fun effectivePort(uri: URI): Int =
        when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            uri.scheme.equals("http", ignoreCase = true) -> 80
            else -> -1
        }

    private fun automaticFlowAvailable(): Boolean =
        properties.corePolicyReady() && verificationPort.isConfigured()

    private fun supportAction(): String =
        if (properties.supportEmailReady()) {
            """
            <a class="button secondary" href="mailto:${escape(properties.supportEmail)}">
              ${escape(properties.supportEmail)}로 삭제 요청
            </a>
            """.trimIndent()
        } else {
            """
            <div class="warning">
              지원 접수 주소가 아직 설정되지 않았습니다. 운영자에게 계정 삭제 지원 주소 공개를 요청하세요.
            </div>
            """.trimIndent()
        }

    private fun html(status: HttpStatus, body: String): ResponseEntity<String> =
        ResponseEntity.status(status)
            .contentType(MediaType("text", "html", Charsets.UTF_8))
            .cacheControl(CacheControl.noStore())
            .header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
            .header("Referrer-Policy", "no-referrer")
            .header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
            .header("X-Frame-Options", "DENY")
            .header("X-Content-Type-Options", "nosniff")
            .body(body)

    private fun layout(title: String, body: String): String =
        """
        <!doctype html>
        <html lang="ko">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1" />
          <meta name="robots" content="index,follow" />
          <title>${escape(title)} | ${escape(properties.appName)}</title>
          <style>
            :root {
              color-scheme: light;
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
              --ink: #111827; --muted: #5f6877; --line: #dfe5ee; --soft: #f4f7fb;
              --blue: #1769e0; --danger: #b42318; --danger-soft: #fff1f0;
            }
            * { box-sizing: border-box; }
            body { margin: 0; background: var(--soft); color: var(--ink); line-height: 1.65; }
            main { width: min(100% - 32px, 760px); margin: 0 auto; padding: 56px 0 72px; }
            .brand { display: inline-block; margin-bottom: 48px; color: var(--ink); font-size: 21px;
                     font-weight: 900; text-decoration: none; }
            article { padding: clamp(24px, 6vw, 52px); border: 1px solid var(--line);
                      border-radius: 28px; background: #fff; box-shadow: 0 18px 56px rgba(28, 39, 57, .08); }
            .eyebrow { margin: 0; color: var(--blue); font-size: 12px; font-weight: 900; letter-spacing: .08em; }
            .warning-text { color: var(--danger); }
            h1 { margin: 10px 0 16px; font-size: clamp(32px, 7vw, 50px); line-height: 1.13; letter-spacing: -.045em; }
            h2 { margin: 34px 0 10px; font-size: 20px; }
            p, li { color: var(--muted); }
            .lead { margin: 0 0 24px; font-size: 17px; }
            ol { padding-left: 22px; }
            li + li { margin-top: 7px; }
            form { display: grid; gap: 12px; margin-top: 32px; padding-top: 28px; border-top: 1px solid var(--line); }
            label, strong { font-weight: 850; }
            input[type="email"], input[type="text"] {
              width: 100%; min-height: 50px; padding: 12px 14px; border: 1px solid #b8c2d1;
              border-radius: 12px; background: #fff; color: var(--ink); font: inherit;
            }
            input:focus { outline: 3px solid rgba(23, 105, 224, .2); border-color: var(--blue); }
            button, .button { display: inline-flex; min-height: 50px; align-items: center; justify-content: center;
                              padding: 12px 18px; border: 0; border-radius: 12px; background: var(--blue);
                              color: #fff; font: inherit; font-weight: 900; text-decoration: none; cursor: pointer; }
            .danger { background: var(--danger); }
            .secondary { background: var(--ink); }
            .notice, .warning { margin: 24px 0; padding: 18px; border-radius: 16px; background: #eef4ff; color: #344054; }
            .warning { background: var(--danger-soft); color: #7a271a; }
            .success { background: #ecfdf3; color: #067647; }
            .notice code, .notice span { display: block; margin-top: 8px; overflow-wrap: anywhere; }
            .notice code { color: var(--ink); font-size: 14px; }
            .check { display: flex; gap: 10px; align-items: flex-start; color: #344054; font-weight: 700; }
            .check input { width: 20px; height: 20px; flex: 0 0 auto; }
            .help { margin-top: 24px; font-size: 14px; }
            a { color: var(--blue); }
            @media (max-width: 520px) { main { padding-top: 28px; } .brand { margin-bottom: 26px; } }
          </style>
        </head>
        <body>
          <main>
            <a class="brand" href="/">${escape(properties.appName)}</a>
            <article>$body</article>
          </main>
        </body>
        </html>
        """.trimIndent()

    private fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private companion object {
        const val CONTENT_SECURITY_POLICY =
            "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; " +
                "base-uri 'none'; frame-ancestors 'none'"
    }
}
