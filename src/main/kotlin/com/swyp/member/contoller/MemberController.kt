package com.swyp.member.controller

import com.swyp.global.common.ApiResponse
import com.swyp.member.domain.MemberDto
import com.swyp.member.application.MemberUseCase
import com.swyp.member.domain.LoginType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/members")
@Tag(name = "Member", description = "회원 관리 API")
class MemberController(
    private val memberUseCase: MemberUseCase
) {

    @Operation(summary = "회원가입")
    @PostMapping
    fun signUp(@RequestBody request: SignUpRequest): ApiResponse<MemberDto> {
        val memberDto = MemberDto(
            email = request.email,
            password = request.password,
            name = request.name
        )
        val result = memberUseCase.signUp(memberDto)
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