package com.noLate.member.application.useCase

import com.noLate.auth.application.RefreshTokenService
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.JwtTokenProvider
import com.noLate.member.application.service.MemberProfileService
import com.noLate.member.application.service.MemberConsentService
import com.noLate.member.application.service.MemberService
import com.noLate.member.application.service.MemberSettingService
import com.noLate.member.application.service.MemberValidator
import com.noLate.member.application.service.SocialIdentityVerifier
import com.noLate.member.application.service.VerifiedSocialIdentity
import com.noLate.member.application.service.AccountCleanupService
import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.Member
import com.noLate.member.domain.member.MemberDto
import com.noLate.member.domain.consent.MemberConsentSource
import com.noLate.member.domain.consent.SignupConsentCommand
import com.noLate.member.domain.memberSetting.MemberSettingDto
import com.noLate.member.domain.profile.MemberProfileDto
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.Locale

@Component
class MemberUseCase(
    private val memberService: MemberService,
    private val memberSettingService: MemberSettingService,
    private val memberProfileService: MemberProfileService,
    private val jwtTokenProvider: JwtTokenProvider,
    private val passwordEncoder: PasswordEncoder,
    private val memberValidator: MemberValidator,
    private val refreshTokenService: RefreshTokenService,
    private val memberConsentService: MemberConsentService,
    private val socialIdentityVerifier: SocialIdentityVerifier? = null,
    private val accountCleanupService: AccountCleanupService? = null,
) {

    /**
     * COMMON 회원가입
     * 1) 입력값/중복 검증
     * 2) 비밀번호 암호화
     * 3) 회원 저장 (COMMON 타입으로)
     * 4) 기본 설정(MemberSetting) 생성
    */
    @Transactional
    fun signUp(memberDto: MemberDto, consents: SignupConsentCommand): MemberDto {
        // 계정을 만들기 전에 동의 여부와 문서 버전을 검증한다. 이후 저장 실패도 같은
        // 트랜잭션에서 롤백되어 회원과 동의 이력이 서로 다른 상태로 남지 않는다.
        memberConsentService.validateRequiredSignupConsents(consents)

        // 1) COMMON 회원가입 검증 (이메일, 비번, 중복 등)
        memberValidator.validateCommonSignUp(memberDto)

        // 2) 비밀번호 암호화
        val encodedPassword = passwordEncoder.encode(memberDto.password!!)

        // 3) DTO → 엔티티 변환 + 암호화된 비번, 로그인타입 세팅
        val entity = memberDto.toEntity().apply {
            this.password = encodedPassword
            this.loginType = LoginType.COMMON
            this.name = memberDto.name?.trim()
        }

        // 4) 회원 저장 (Service는 DTO 반환)
        val savedMemberDto: MemberDto = memberService.addMember(entity)

        // 5) 기본 설정 생성
        memberSettingService.createDefaultSetting(
            MemberSettingDto().apply { memberId = requireNotNull(savedMemberDto.id) }
        )

        memberProfileService.createDefaultProfile(requireNotNull(savedMemberDto.id))

        memberConsentService.recordRequiredSignupConsents(
            memberId = requireNotNull(savedMemberDto.id),
            consents = consents,
            source = MemberConsentSource.COMMON_SIGNUP,
        )

        return savedMemberDto
    }

    /**
     *   로그인 (COMMON + SNS)
     * - COMMON : 이메일/비번 검증 후 토큰 발급
     * - SNS    : 기존 회원만 로그인. 신규 회원은 별도 동의 가입 API를 사용
     * - 공통 : accessToken + refreshToken 발급 및 refreshToken 저장
     */
    @Transactional
    fun login(requestDto: MemberDto): MemberDto {
        if (requestDto.loginType != LoginType.COMMON) {
            throw BusinessException(
                ErrorCode.INVALID_CREDENTIALS,
                "SNS 로그인은 공급자 인증 토큰이 필요합니다.",
            )
        }
        return issueTokens(memberValidator.validateAndGetMemberForCommonLogin(requestDto))
    }

    /** 공개 SNS 인증 경로. 클라이언트가 보낸 snsId/profile은 사용하지 않는다. */
    @Transactional
    fun loginSns(
        loginType: LoginType,
        providerToken: String?,
        nonce: String? = null,
    ): MemberDto {
        val identity = verifySocialIdentity(loginType, providerToken, nonce)
        val member = memberService.findByLoginTypeAndSnsId(loginType, identity.subject)
            ?.apply { isNewMember = false }
            ?: throw BusinessException(ErrorCode.SNS_SIGNUP_REQUIRED)
        return issueTokens(member)
    }

    @Transactional(readOnly = true)
    fun isSnsMemberRegistered(
        loginType: LoginType,
        providerToken: String?,
        nonce: String? = null,
    ): Boolean {
        val identity = verifySocialIdentity(loginType, providerToken, nonce)
        return memberService.findByLoginTypeAndSnsId(loginType, identity.subject) != null
    }

    @Transactional
    fun signUpSns(
        loginType: LoginType,
        providerToken: String?,
        nonce: String?,
        consents: SignupConsentCommand,
    ): MemberDto {
        val identity = verifySocialIdentity(loginType, providerToken, nonce)
        return signUpVerifiedSns(loginType, identity, consents)
    }

    private fun verifySocialIdentity(
        loginType: LoginType,
        providerToken: String?,
        nonce: String?,
    ): VerifiedSocialIdentity = requireNotNull(socialIdentityVerifier) {
        "SocialIdentityVerifier bean is required."
    }.verify(loginType, providerToken, nonce)

    private fun signUpVerifiedSns(
        loginType: LoginType,
        identity: VerifiedSocialIdentity,
        consents: SignupConsentCommand,
    ): MemberDto {
        memberConsentService.validateRequiredSignupConsents(consents)
        if (loginType == LoginType.COMMON) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "SNS 로그인 유형이 필요합니다.")
        }
        if (memberService.findByLoginTypeAndSnsId(loginType, identity.subject) != null) {
            throw BusinessException(ErrorCode.DUPLICATE_MEMBER, "이미 가입된 SNS 계정입니다.")
        }

        // 이메일 주소의 local/domain 표기는 공급자마다 대소문자가 달라질 수 있다.
        // COMMON 가입과 같은 정규형으로 비교·저장해야 동일 이메일 계정이 DB의
        // case-sensitive unique constraint를 우회하지 않는다.
        val verifiedEmail = identity.email
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf(String::isNotBlank)
        val name = identity.name ?: verifiedEmail ?: "사용자"
        val email = verifiedEmail ?: createSyntheticSnsEmail(loginType, identity.subject)
        if (verifiedEmail != null && memberService.findByEmail(email) != null) {
            throw BusinessException(ErrorCode.ACCOUNT_LINK_REQUIRED)
        }
        val saved = memberService.addMember(
            Member().apply {
                snsId = identity.subject
                this.name = name
                this.loginType = loginType
                this.email = email
                password = ""
            }
        ).apply { isNewMember = true }
        val memberId = requireNotNull(saved.id)
        memberSettingService.createDefaultSetting(MemberSettingDto().apply { this.memberId = memberId })
        memberProfileService.createDefaultProfile(memberId)
        memberConsentService.recordRequiredSignupConsents(
            memberId = memberId,
            consents = consents,
            source = MemberConsentSource.SNS_SIGNUP,
        )
        return issueTokens(saved)
    }

    private fun issueTokens(memberDto: MemberDto): MemberDto {
        val memberId = requireNotNull(memberDto.id) { "member.id가 없습니다." }
        val memberName = requireNotNull(memberDto.name) { "member.name이 없습니다." }

        // 3) accessToken + refreshToken 발급
        val accessToken = jwtTokenProvider.createAccessToken(memberId, memberName)
        val refreshToken = jwtTokenProvider.createRefreshToken(memberId, memberName)

        // 4) refreshToken 저장 (기존 것들 정리하는 정책은 RefreshTokenService 내에서 처리)
        val refreshExpiry = jwtTokenProvider.getRefreshTokenExpiryLocalDateTime()
        refreshTokenService.saveNewToken(memberId, refreshToken, refreshExpiry)

        return memberDto.apply {
            this.accessToken = accessToken
            this.refreshToken = refreshToken
        }
    }

    private fun createSyntheticSnsEmail(loginType: LoginType?, snsId: String): String {
        val provider = loginType?.name?.lowercase() ?: "sns"
        val safeSnsId = snsId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "${provider}_$safeSnsId@social.local"
    }

    /**
     * refreshToken만으로 로그인 (앱 재실행 시 자동 로그인용)
     *
     * - 클라이언트는 refreshToken 하나만 보내면
     *   → 서버가 토큰 검증 + 회원 조회 + 새 access/refresh 발급까지 수행
     */
    @Transactional
    fun tokenLogin(refreshToken: String): MemberDto {
        // 내부적으로는 refresh() 와 동일한 동작을 하도록 추상화
        return reissueTokens(refreshToken)
    }

    /**
     *  로그아웃
     * - 전달받은 refreshToken 을 폐기(revoke)
     * - accessToken 은 짧게 가져가고 별도 블랙리스트는 사용하지 않는 정책
     */
    @Transactional
    fun logout(refreshToken: String) {
        // 1) 토큰이 유효하지 않더라도(DB에는 남아있을 수 있으므로) revoke는 시도
        if (!jwtTokenProvider.validateToken(refreshToken) ||
            !runCatching { jwtTokenProvider.isRefreshToken(refreshToken) }.getOrDefault(false)
        ) {
            // 이미 만료되었더라도 DB의 토큰은 폐기
            refreshTokenService.revokeToken(refreshToken)
            return
        }

        // 2) 토큰에서 memberId 추출
        val memberIdFromToken = jwtTokenProvider.getMemberIdFromToken(refreshToken)

        // 3) DB에 있는 토큰 검증 (존재, 소유자 일치, 만료/폐기 여부)
        val stored = refreshTokenService.validateAndGet(refreshToken)
        if (stored.memberId != memberIdFromToken) {
            throw BusinessException(ErrorCode.INVALID_TOKEN, "토큰 소유자가 일치하지 않습니다.")
        }

        // 4) 모든 세션과 기기 토큰을 제거하고 기존 access token도 즉시 무효화한다.
        memberService.invalidateSessions(memberIdFromToken)
        if (accountCleanupService != null) {
            accountCleanupService.logoutAll(memberIdFromToken)
        } else {
            // 단위 테스트 호환 fallback. 운영에서는 AccountCleanupService bean이 항상 주입된다.
            refreshTokenService.deleteAllByMemberId(memberIdFromToken)
        }
    }

    /**
     *  토큰 재발급 전용 (Refresh API)
     * - refreshToken의 유효성을 확인하고
     * - 새 accessToken + refreshToken 세트를 발급 후 반환
     *
     * tokenLogin() 과 로직은 동일하고, API 레벨에서 의미만 다르게 가져갈 수 있음.
     */
    @Transactional
    fun refresh(refreshToken: String): MemberDto {
        return reissueTokens(refreshToken)
    }

    @Transactional(readOnly = true)
    fun getCurationStatus(memberId: Long): Boolean {
        return memberService.getFindMemberId(memberId)
            .orElseThrow {
                BusinessException(ErrorCode.MEMBER_NOT_FOUND, "회원 정보를 찾을 수 없습니다.")
            }
            .curationCompleted
    }

    @Transactional
    fun completeCuration(memberId: Long): Boolean {
        val member = memberService.getFindMemberId(memberId)
            .orElseThrow {
                BusinessException(ErrorCode.MEMBER_NOT_FOUND, "회원 정보를 찾을 수 없습니다.")
            }

        // 완료 API는 재시도될 수 있다. 이미 완료된 회원은 불필요한 UPDATE 없이 성공으로 응답한다.
        if (!member.curationCompleted) {
            member.curationCompleted = true
            memberService.updateMember(member)
        }
        return true
    }

    /**
     * - refreshToken 기준으로
     *   1) JWT 유효성 검사
     *   2) DB 상태 검사 (존재, revoked, 만료)
     *   3) 회원 조회
     *   4) 새 accessToken + refreshToken 발급
     *   5) 기존 refreshToken 폐기 + 새 refreshToken 저장
     *   6) MemberDto + 새 토큰 세트 반환
     *
     * tokenLogin() / refresh() 에서 공통으로 사용
     */
    private fun reissueTokens(refreshToken: String): MemberDto {
        // 1) JWT 자체 유효성 검사 (서명 + 만료)
        if (!jwtTokenProvider.validateToken(refreshToken) ||
            !runCatching { jwtTokenProvider.isRefreshToken(refreshToken) }.getOrDefault(false)
        ) {
            throw BusinessException(ErrorCode.INVALID_TOKEN, "유효하지 않은 리프레시 토큰입니다.")
        }

        val memberIdFromToken = jwtTokenProvider.getMemberIdFromToken(refreshToken)

        // 3) 회원 조회 (Optional<Member> 라고 가정)
        val memberOpt = memberService.getFindMemberId(memberIdFromToken)
        if (memberOpt.isEmpty) {
            throw BusinessException(ErrorCode.MEMBER_NOT_FOUND, "회원 정보를 찾을 수 없습니다.")
        }
        val member: Member = memberOpt.get()

        val memberName = member.name ?: throw BusinessException(
            ErrorCode.MEMBER_NOT_FOUND_NAME,
            "회원 이름이 없습니다."
        )

        // 4) 새 accessToken + refreshToken 발급
        val newAccessToken = jwtTokenProvider.createAccessToken(memberIdFromToken, memberName)
        val newRefreshToken = jwtTokenProvider.createRefreshToken(memberIdFromToken, memberName)
        val newRefreshExpiry = jwtTokenProvider.getRefreshTokenExpiryLocalDateTime()

        // 5) row lock으로 기존 refreshToken을 단 한 번만 소비하고 새 token으로 회전
        refreshTokenService.consumeAndRotate(
            refreshToken = refreshToken,
            expectedMemberId = memberIdFromToken,
            newToken = newRefreshToken,
            newExpiresAt = newRefreshExpiry,
        )

        // 6) 유저 정보 + 새 토큰 세트 반환
        return member.toDto().apply {
            this.accessToken = newAccessToken
            this.refreshToken = newRefreshToken
        }
    }

    /**
     * 🔑 비밀번호 변경 (COMMON 계정만)
     * - 기존 비밀번호 검증 후 새 비밀번호로 교체
     */
    @Transactional
    fun changePassword(memberId: Long, currentPassword: String, newPassword: String) {
        // 1) 회원 조회
        val member = memberService.getFindMemberId(memberId)
            .orElseThrow {
                BusinessException(ErrorCode.MEMBER_NOT_FOUND, "회원 정보를 찾을 수 없습니다.")
            }

        // 2) COMMON 계정만 비밀번호 변경 허용
        if (member.loginType != LoginType.COMMON) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "SNS 로그인 계정은 비밀번호를 변경할 수 없습니다."
            )
        }

        // 3) 기존 비밀번호 검증
        val encoded = member.password
            ?: throw BusinessException(ErrorCode.INVALID_STATE, "저장된 비밀번호가 없습니다.")

        if (!passwordEncoder.matches(currentPassword, encoded)) {
            throw BusinessException(ErrorCode.INVALID_CREDENTIALS, "기존 비밀번호가 일치하지 않습니다.")
        }

        // 4) 새 비밀번호 검증 (간단 버전 – 필요하면 Validator로 분리)
        memberValidator.validatePassword(newPassword)

        // 5) 새 비밀번호 저장
        val newEncoded = passwordEncoder.encode(newPassword)
        member.password = newEncoded

        memberService.updateMember(member)  // 반환값은 굳이 안 써도 됨
    }

    @Transactional
    fun withdraw(memberId: Long, passwordForCheck: String?) {
        // 1) 회원 조회
        val member = memberService.getFindMemberId(memberId)
            .orElseThrow {
                BusinessException(ErrorCode.MEMBER_NOT_FOUND, "회원 정보를 찾을 수 없습니다.")
            }

        // 2) COMMON 계정은 비밀번호 검증
        if (member.loginType == LoginType.COMMON) {
            val raw = passwordForCheck
                ?: throw BusinessException(ErrorCode.INVALID_INPUT, "비밀번호가 필요합니다.")

            val encoded = member.password
                ?: throw BusinessException(ErrorCode.INVALID_STATE, "저장된 비밀번호가 없습니다.")

            if (!passwordEncoder.matches(raw, encoded)) {
                throw BusinessException(ErrorCode.INVALID_CREDENTIALS, "비밀번호가 일치하지 않습니다.")
            }
        }

        val id = requireNotNull(member.id) { "member.id 가 없습니다." }

        memberService.invalidateSessions(id)
        if (accountCleanupService != null) {
            accountCleanupService.withdraw(member)
            memberService.updateMember(member)
        } else {
            refreshTokenService.deleteAllByMemberId(memberId)
            val byMemberId = memberSettingService.getByMemberId(memberId)
            memberSettingService.softDelete(byMemberId.toEntity())
            memberService.softDelete(member)
        }
    }



    // Legacy members may not have a profile row yet, so this read path can create
    // the default profile and must not run in a read-only transaction.
    @Transactional
    fun getMyProfile(memberId: Long): MemberProfileDto {
        // 회원 존재 여부 먼저 확인해도 좋음
        val exists = memberService.getFindMemberId(memberId)
        if (exists.isEmpty) {
            throw BusinessException(ErrorCode.MEMBER_NOT_FOUND, "회원 정보를 찾을 수 없습니다.")
        }

        return memberProfileService.getByMemberId(memberId)
            ?: memberProfileService.createDefaultProfile(memberId)
    }

    /**
     * ✏️ 내 프로필 수정
     */
    @Transactional
    fun updateMyProfile(memberId: Long, dto: MemberProfileDto): MemberProfileDto {
        val exists = memberService.getFindMemberId(memberId)
        if (exists.isEmpty) {
            throw BusinessException(ErrorCode.MEMBER_NOT_FOUND, "회원 정보를 찾을 수 없습니다.")
        }

        // 여기서 nickname 길이, 금지어 등 검증을 Validator로 뺄 수도 있음
        return memberProfileService.updateProfile(memberId, dto)
    }
}
