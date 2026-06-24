package com.noLate.member.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.member.application.useCase.MemberUseCase
import com.noLate.member.domain.member.MemberDto
import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.profile.MemberProfileDto
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/member")
@Tag(name = "Member", description = "회원 관리 API")
class MemberController(
    private val memberUseCase : MemberUseCase
) {

    @Operation(summary = "회원가입")
    @PostMapping("/auth/sign-up")
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
    @PostMapping("/auth/login")
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
    @PostMapping("/auth/sns-login")
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

    @Operation(summary =  "토큰 로그인")
    @PostMapping("/auth/token-login")
    fun tokenLogin(@RequestBody request: TokenLoginRequest) : ApiResponse<MemberDto> {
        val result = memberUseCase.tokenLogin(request.refreshToken)
        return ApiResponse.success(result)
    }

    @Operation(summary = "Refresh token 재발급")
    @PostMapping("/auth/refresh")
    fun refresh(@RequestBody request: TokenLoginRequest): ApiResponse<MemberDto> {
        val result = memberUseCase.refresh(request.refreshToken)
        return ApiResponse.success(result)
    }

    @Operation(summary = "로그아웃")
    @PostMapping("/auth/logout")
    fun logout(@RequestBody request: TokenLoginRequest): ApiResponse<Unit> {
        memberUseCase.logout(request.refreshToken)
        return ApiResponse.success(Unit)
    }

    @Operation(summary = "내 프로필 조회")
    @GetMapping("/profile")
    fun getMyProfile(
        @AuthenticationPrincipal principal: MemberPrincipal?,
    ): ApiResponse<MemberProfileDto> {
        val result = memberUseCase.getMyProfile(requireMemberId(principal))
        return ApiResponse.success(result)
    }

    @Operation(summary = "내 프로필 수정")
    @PutMapping("/profile")
    fun updateMyProfile(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: UpdateProfileRequest,
    ): ApiResponse<MemberProfileDto> {
        val memberId = requireMemberId(principal)
        val result = memberUseCase.updateMyProfile(
            memberId = memberId,
            dto = MemberProfileDto(
                memberId = memberId,
                nickname = request.nickname,
                imgId = request.imgId,
                intro = request.intro,
            ),
        )
        return ApiResponse.success(result)
    }

    @Operation(summary = "비밀번호 변경")
    @PatchMapping("/password")
    fun changePassword(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: ChangePasswordRequest,
    ): ApiResponse<Unit> {
        memberUseCase.changePassword(
            memberId = requireMemberId(principal),
            currentPassword = request.currentPassword,
            newPassword = request.newPassword,
        )
        return ApiResponse.success(Unit)
    }

    @Operation(summary = "회원 탈퇴")
    @DeleteMapping("/withdraw")
    fun withdraw(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody(required = false) request: WithdrawRequest?,
    ): ApiResponse<Unit> {
        memberUseCase.withdraw(
            memberId = requireMemberId(principal),
            passwordForCheck = request?.password,
        )
        return ApiResponse.success(Unit)
    }

    private fun requireMemberId(principal: MemberPrincipal?): Long =
        principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
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

data class TokenLoginRequest(
    val refreshToken: String
)

data class UpdateProfileRequest(
    val nickname: String? = null,
    val imgId: Long? = null,
    val intro: String? = null,
)

data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
)

data class WithdrawRequest(
    val password: String? = null,
)

data class UpdateMemberRequest(
    val email: String?,
    val name: String
)
