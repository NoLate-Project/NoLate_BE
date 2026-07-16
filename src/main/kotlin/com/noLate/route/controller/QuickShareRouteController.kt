package com.noLate.route.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.route.quickshare.QuickSharePlace
import com.noLate.route.quickshare.QuickShareRouteOption
import com.noLate.route.quickshare.QuickShareRouteRequest
import com.noLate.route.quickshare.QuickShareRouteService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/routes/quick-share")
class QuickShareRouteController(
    private val service: QuickShareRouteService,
) {
    @GetMapping("/places")
    fun searchPlaces(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestParam query: String,
    ): ApiResponse<List<QuickSharePlace>> {
        requireMember(principal)
        return ApiResponse.success(service.searchPlaces(query))
    }

    @PostMapping("/options")
    fun getRouteOptions(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: QuickShareRouteRequest,
    ): ApiResponse<List<QuickShareRouteOption>> {
        requireMember(principal)
        return ApiResponse.success(service.getRouteOptions(request))
    }

    private fun requireMember(principal: MemberPrincipal?) {
        if (principal == null) throw BusinessException(ErrorCode.UNAUTHORIZED)
    }
}

