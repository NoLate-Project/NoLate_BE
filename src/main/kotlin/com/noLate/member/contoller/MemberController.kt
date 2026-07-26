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
        val result = memberUseCase.loginSns(
            loginType = request.loginType,
            providerToken = request.providerToken,
            nonce = request.nonce,
            authorizationCode = request.authorizationCode,
        )
        return ApiResponse.success(result)
    }

    @Operation(summary = "SNS 가입 여부 확인")
    @PostMapping("/auth/sns-registration")
    fun getSnsRegistrationStatus(
        @RequestBody request: SnsRegistrationRequest,
    ): ApiResponse<SnsRegistrationStatusResponse> {
        val registered = memberUseCase.isSnsMemberRegistered(
            loginType = request.loginType,
            providerToken = request.providerToken,
            nonce = request.nonce,
        )
        return ApiResponse.success(SnsRegistrationStatusResponse(registered = registered))
    }

    @Operation(summary = "SNS 신규 회원가입")
    @PostMapping("/auth/sns-sign-up")
    fun snsSignUp(@RequestBody request: SnsSignUpRequest): ApiResponse<MemberDto> {
        val result = memberUseCase.signUpSns(
            loginType = request.loginType,
            providerToken = request.providerToken,
            nonce = request.nonce,
            consents = request.consents.toCommand(),
            authorizationCode = request.authorizationCode,
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

    @Operation(
        summary = "로그아웃",
        description = "제시한 refresh token session만 compare-and-revoke하며 stale/replay는 성공 no-op입니다.",
    )
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
        val completed = memberUseCase.completeCuration(
            memberId = requireMemberId(principal),
            presentedSessionGeneration = requireSessionGeneration(principal),
        )
        return ApiResponse.success(CurationStatusResponse(curationCompleted = completed))
    }

    @Operation(summary = "내 프로필 조회")
    @GetMapping("/profile")
    fun getMyProfile(
        @AuthenticationPrincipal principal: MemberPrincipal?,
    ): ApiResponse<MemberProfileDto> {
        val result = memberUseCase.getMyProfile(
            memberId = requireMemberId(principal),
            presentedSessionGeneration = requireSessionGeneration(principal),
        )
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
            presentedSessionGeneration = requireSessionGeneration(principal),
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
            presentedSessionGeneration = requireSessionGeneration(principal),
        )
        return ApiResponse.success(Unit)
    }

    @Operation(
        summary = "회원 탈퇴",
        description = "현재 access token session generation을 DB lock 안에서 재검증하는 파괴적 작업입니다.",
    )
    @DeleteMapping("/withdraw")
    fun withdraw(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody(required = false) request: WithdrawRequest?,
    ): ApiResponse<WithdrawResponse> {
        val result = memberUseCase.withdraw(
            memberId = requireMemberId(principal),
            presentedSessionGeneration = requireSessionGeneration(principal),
            passwordForCheck = request?.password,
        )
        return ApiResponse.success(
            WithdrawResponse(
                manualAppleRevocationRequired = result.manualAppleRevocationRequired,
            )
        )
    }

    private fun requireMemberId(principal: MemberPrincipal?): Long =
        principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED)

    private fun requireSessionGeneration(principal: MemberPrincipal?): Long =
        principal?.accessTokenSessionGeneration
            ?: throw BusinessException(
                if (principal == null) ErrorCode.UNAUTHORIZED else ErrorCode.INVALID_TOKEN,
            )
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

// 카카오/네이버는 access token, Apple은 identity token을 providerToken으로 보낸다.
// Apple authorizationCode는 가입 여부 조회에서 소비하지 않고 실제 로그인/가입에서만 서버가
// 교환한다. Apple nonce를 사용한 클라이언트는 nonce도 반드시 함께 보낸다.
data class SnsLoginRequest(
    val loginType: LoginType,
    val providerToken: String,
    val authorizationCode: String? = null,
    val nonce: String? = null,
)

data class SnsRegistrationRequest(
    val loginType: LoginType,
    val providerToken: String,
    val authorizationCode: String? = null,
    val nonce: String? = null,
)

data class SnsRegistrationStatusResponse(
    val registered: Boolean,
)

data class SnsSignUpRequest(
    val loginType: LoginType,
    val providerToken: String,
    val authorizationCode: String? = null,
    val nonce: String? = null,
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

data class WithdrawResponse(
    val manualAppleRevocationRequired: Boolean,
)

data class UpdateMemberRequest(
    val email: String?,
    val name: String
)
