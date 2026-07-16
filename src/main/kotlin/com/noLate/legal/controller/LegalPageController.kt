package com.noLate.legal.controller

import com.noLate.legal.domain.LegalDocumentDto
import com.noLate.legal.domain.LegalDocuments
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/legal")
class LegalPageController {

    @GetMapping("/terms-of-service", produces = [MediaType.TEXT_HTML_VALUE])
    fun getTermsOfServicePage(): String =
        renderLegalDocumentHtml(LegalDocuments.termsOfService)

    @GetMapping("/privacy-collection-consent", produces = [MediaType.TEXT_HTML_VALUE])
    fun getPrivacyCollectionConsentPage(): String =
        renderLegalDocumentHtml(LegalDocuments.privacyCollectionConsent)

    @GetMapping("/privacy-policy", produces = [MediaType.TEXT_HTML_VALUE])
    fun getPrivacyPolicyPage(): String =
        renderLegalDocumentHtml(LegalDocuments.privacyPolicy)

    private fun renderLegalDocumentHtml(document: LegalDocumentDto): String {
        val sections = document.sections.joinToString("\n") { section ->
            val paragraphs = section.body.joinToString("\n") { "<li>${it.escapeHtml()}</li>" }
            """
            <section>
              <h2>${section.title.escapeHtml()}</h2>
              <ul>
                $paragraphs
              </ul>
            </section>
            """.trimIndent()
        }

        return """
            <!doctype html>
            <html lang="ko">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>${document.title.escapeHtml()} | NoLate</title>
              <style>
                :root { color-scheme: light dark; }
                body {
                  margin: 0;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                  line-height: 1.68;
                  background: #f8f9fb;
                  color: #15171c;
                }
                main {
                  max-width: 760px;
                  margin: 0 auto;
                  padding: 44px 20px 64px;
                }
                .eyebrow {
                  color: #69707d;
                  font-size: 13px;
                  font-weight: 800;
                }
                h1 {
                  margin: 8px 0 12px;
                  font-size: 34px;
                  line-height: 1.18;
                  letter-spacing: 0;
                }
                .summary {
                  color: #5f6673;
                  font-size: 15px;
                  font-weight: 600;
                }
                section {
                  margin-top: 18px;
                  padding: 20px;
                  border: 1px solid rgba(0,0,0,0.08);
                  border-radius: 18px;
                  background: #fff;
                }
                h2 {
                  margin: 0 0 10px;
                  font-size: 18px;
                  line-height: 1.35;
                }
                ul {
                  margin: 0;
                  padding-left: 20px;
                }
                li {
                  margin: 7px 0;
                  color: #4f5663;
                  font-size: 14px;
                }
                footer {
                  margin-top: 20px;
                  color: #69707d;
                  font-size: 12px;
                }
                @media (prefers-color-scheme: dark) {
                  body { background: #0f1115; color: #f5f6f8; }
                  .eyebrow, .summary, footer { color: #9aa1ad; }
                  section {
                    background: #17191f;
                    border-color: rgba(255,255,255,0.09);
                  }
                  li { color: #c3c8d0; }
                }
              </style>
            </head>
            <body>
              <main>
                <div class="eyebrow">시행일 ${document.effectiveDate.escapeHtml()} · 버전 ${document.version.escapeHtml()}</div>
                <h1>${document.title.escapeHtml()}</h1>
                <p class="summary">${document.summary.escapeHtml()}</p>
                $sections
                <footer>NoLate 정책 문의: privacy@nolate.jinuk.dev</footer>
              </main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun String.escapeHtml(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}
