package com.swyp.member.controller

import com.swyp.global.common.ApiResponse
import com.swyp.member.application.useCase.MemberUseCase
import com.swyp.member.domain.Member.MemberDto
import com.swyp.member.domain.Member.LoginType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/members")
@Tag(name = "Member", description = "회원 관리 API")
class MemberController(
    private val memberUseCase : MemberUseCase
) {

    @Operation(summary = "회원가입")
    @PostMapping
    fun signUp(@RequestBody request: SignUpRequest): ApiResponse<MemberDto> {
        val memberDto = MemberDto(
            email = request.email,
            password = request.password,
            name = request.name,
            loginType = LoginType.COMMON
        )
        val result = memberUseCase.signUp(memberDto)
        return ApiResponse.success(result)
    }

    @Operation(summary = "일반 로그인")
    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): ApiResponse<MemberDto> {
        val memberDto = MemberDto(
            email = request.email,
            password = request.password,
            loginType = LoginType.COMMON
        )
        val result = memberUseCase.login(memberDto)
        return ApiResponse.success(result)
    }

    @Operation(summary = "SNS 로그인")
    @PostMapping("/sns-login")
    fun snsLogin(@RequestBody request: SnsLoginRequest): ApiResponse<MemberDto> {
        val memberDto = MemberDto(
            email = request.email,
            name = request.name,
            loginType = request.loginType,
            snsId = request.snsId
        )
        val result = memberUseCase.login(memberDto)
        return ApiResponse.success(result)
    }
}

data class SignUpRequest(
    val email: String,
    val password: String,
    val name: String
)

data class LoginRequest(
    val email: String,
    val password: String
)

// 카카오/구글 SDK에서 검증 완료된 값들을 보내준다고 가정
data class SnsLoginRequest(
    val loginType: LoginType, // KAKAO / GOOGLE / ...
    val snsId: String,
    val email: String?,
    val name: String
)

data class UpdateMemberRequest(
    val email: String?,
    val name: String
)