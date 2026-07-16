package com.noLate.member.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.member.application.useCase.MemberUseCase
import com.noLate.member.domain.consent.SignupConsentCommand
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
        val result = memberUseCase.signUp(memberDto, request.consents.toCommand())
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

    @Operation(summary = "SNS 가입 여부 확인")
    @PostMapping("/auth/sns-registration")
    fun getSnsRegistrationStatus(
        @RequestBody request: SnsRegistrationRequest,
    ): ApiResponse<SnsRegistrationStatusResponse> {
        val registered = memberUseCase.isSnsMemberRegistered(request.loginType, request.snsId)
        return ApiResponse.success(SnsRegistrationStatusResponse(registered = registered))
    }

    @Operation(summary = "SNS 신규 회원가입")
    @PostMapping("/auth/sns-sign-up")
    fun snsSignUp(@RequestBody request: SnsSignUpRequest): ApiResponse<MemberDto> {
        val result = memberUseCase.signUpSns(
            requestDto = MemberDto(
                email = request.email,
                name = request.name,
                loginType = request.loginType,
                snsId = request.snsId,
            ),
            consents = request.consents.toCommand(),
        )
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

    @Operation(summary = "큐레이션 완료 상태 조회")
    @GetMapping("/curation")
    fun getCurationStatus(
        @AuthenticationPrincipal principal: MemberPrincipal?,
    ): ApiResponse<CurationStatusResponse> {
        val completed = memberUseCase.getCurationStatus(requireMemberId(principal))
        return ApiResponse.success(CurationStatusResponse(curationCompleted = completed))
    }

    @Operation(summary = "큐레이션 완료 처리")
    @PatchMapping("/curation/complete")
    fun completeCuration(
        @AuthenticationPrincipal principal: MemberPrincipal?,
    ): ApiResponse<CurationStatusResponse> {
        val completed = memberUseCase.completeCuration(requireMemberId(principal))
        return ApiResponse.success(CurationStatusResponse(curationCompleted = completed))
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
    val name: String,
    val consents: SignupConsentRequest,
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

data class SnsRegistrationRequest(
    val loginType: LoginType,
    val snsId: String,
)

data class SnsRegistrationStatusResponse(
    val registered: Boolean,
)

data class SnsSignUpRequest(
    val loginType: LoginType,
    val snsId: String,
    val email: String?,
    val name: String,
    val consents: SignupConsentRequest,
)

data class SignupConsentRequest(
    val termsVersion: String,
    val privacyCollectionVersion: String,
    val termsAgreed: Boolean,
    val privacyCollectionAgreed: Boolean,
) {
    fun toCommand() = SignupConsentCommand(
        termsVersion = termsVersion,
        privacyCollectionVersion = privacyCollectionVersion,
        termsAgreed = termsAgreed,
        privacyCollectionAgreed = privacyCollectionAgreed,
    )
}

data class TokenLoginRequest(
    val refreshToken: String
)

data class CurationStatusResponse(
    val curationCompleted: Boolean,
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
