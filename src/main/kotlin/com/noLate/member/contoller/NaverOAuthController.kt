package com.noLate.member.controller

import com.noLate.global.common.ApiResponse
import com.noLate.member.application.service.NaverOAuthIdentity
import com.noLate.member.application.service.NaverOAuthService
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder

@RestController
@RequestMapping("/api/member/auth/naver")
class NaverOAuthController(private val service: NaverOAuthService) {
    /** The registered HTTPS callback forwards only the code/state to our fixed app scheme. */
    @GetMapping("/callback")
    fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?,
    ): ResponseEntity<Void> {
        val target = UriComponentsBuilder.fromUriString(NaverOAuthService.APP_RETURN_URL).apply {
            if (error != null || code.isNullOrBlank() || state.isNullOrBlank()) {
                queryParam("error", "authorization_failed")
            } else {
                queryParam("code", code)
                queryParam("state", state)
            }
        }.build().encode().toUri()
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(target)
            .cacheControl(CacheControl.noStore())
            .header("Referrer-Policy", "no-referrer")
            .build()
    }

    @PostMapping("/exchange")
    fun exchange(@RequestBody request: NaverCodeExchangeRequest): ApiResponse<NaverOAuthIdentity> =
        ApiResponse.success(service.exchange(request.code, request.codeVerifier))

    @PostMapping("/revoke")
    fun revoke(@RequestBody request: NaverTokenRevokeRequest): ApiResponse<Boolean> {
        service.revoke(request.accessToken)
        return ApiResponse.success(true)
    }
}

data class NaverCodeExchangeRequest(val code: String, val codeVerifier: String)
data class NaverTokenRevokeRequest(val accessToken: String)
