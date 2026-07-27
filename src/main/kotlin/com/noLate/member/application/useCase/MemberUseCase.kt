package com.noLate.member.application.useCase

import com.noLate.auth.application.RefreshTokenService
import com.noLate.auth.apple.AppleCredentialCapture
import com.noLate.auth.apple.AppleTokenLifecycle
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.JwtTokenProvider
import com.noLate.member.application.service.MemberProfileService
import com.noLate.member.application.service.MemberConsentService
import com.noLate.member.application.service.MemberService
import com.noLate.member.application.service.MemberSettingService
import com.noLate.member.application.service.MemberSessionFenceService
import com.noLate.member.application.service.MemberValidator
import com.noLate.member.application.service.SocialIdentityVerifier
import com.noLate.member.application.service.VerifiedSocialIdentity
import com.noLate.member.application.service.AccountCleanupService
import com.noLate.member.application.service.AccountWithdrawalFence
import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.Member
import com.noLate.member.domain.member.MemberDto
import com.noLate.member.domain.consent.MemberConsentSource
import com.noLate.member.domain.consent.SignupConsentCommand
import com.noLate.member.domain.memberSetting.MemberSettingDto
import com.noLate.member.domain.profile.MemberProfileDto
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.Locale
import java.util.Optional

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
    private val memberSessionFenceService: MemberSessionFenceService,
    private val accountCleanupService: AccountCleanupService,
    private val socialIdentityVerifier: SocialIdentityVerifier? = null,
    private val appleTokenLifecycle: Optional<AppleTokenLifecycle> = Optional.empty(),
    private val transactionManager: PlatformTransactionManager? = null,
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
    fun loginSns(
        loginType: LoginType,
        providerToken: String?,
        nonce: String? = null,
        authorizationCode: String? = null,
    ): MemberDto {
        val identity = verifySocialIdentity(loginType, providerToken, nonce)
        val member = memberService.findByLoginTypeAndSnsId(loginType, identity.subject)
            ?.apply { isNewMember = false }
            ?: throw BusinessException(ErrorCode.SNS_SIGNUP_REQUIRED)
        val capture = exchangeAppleCapture(loginType, identity, authorizationCode, nonce)
        return completeWithCaptureCompensation(capture) {
            inWriteTransaction {
                // Re-read inside the final write transaction. A withdrawal/recreation that won
                // while Apple responded must fail before the captured credential can be bound.
                val current = memberService.findByLoginTypeAndSnsId(loginType, identity.subject)
                    ?.takeIf { it.id == member.id }
                    ?.apply { isNewMember = false }
                    ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
                issueTokens(current, capture)
            }
        }
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

    fun signUpSns(
        loginType: LoginType,
        providerToken: String?,
        nonce: String?,
        consents: SignupConsentCommand,
        authorizationCode: String? = null,
    ): MemberDto {
        val identity = verifySocialIdentity(loginType, providerToken, nonce)
        return signUpVerifiedSns(
            loginType = loginType,
            identity = identity,
            consents = consents,
            authorizationCode = authorizationCode,
            nonce = nonce,
        )
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
        authorizationCode: String?,
        nonce: String?,
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
        val capture = exchangeAppleCapture(
            loginType = loginType,
            identity = identity,
            authorizationCode = authorizationCode,
            nonce = nonce,
        )
        return completeWithCaptureCompensation(capture) {
            inWriteTransaction {
                // Race-sensitive uniqueness is checked again after provider I/O. If a competing
                // signup wins, local writes roll back and CAPTURED remains compensation-revocable.
                if (memberService.findByLoginTypeAndSnsId(loginType, identity.subject) != null) {
                    throw BusinessException(
                        ErrorCode.DUPLICATE_MEMBER,
                        "이미 가입된 SNS 계정입니다.",
                    )
                }
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
                memberSettingService.createDefaultSetting(
                    MemberSettingDto().apply { this.memberId = memberId }
                )
                memberProfileService.createDefaultProfile(memberId)
                memberConsentService.recordRequiredSignupConsents(
                    memberId = memberId,
                    consents = consents,
                    source = MemberConsentSource.SNS_SIGNUP,
                )
                issueTokens(saved, capture)
            }
        }
    }

    private fun exchangeAppleCapture(
        loginType: LoginType,
        identity: VerifiedSocialIdentity,
        authorizationCode: String?,
        nonce: String?,
    ): AppleCredentialCapture? {
        if (loginType != LoginType.APPLE) return null
        val lifecycle = appleTokenLifecycle.orElseThrow {
            BusinessException(
                ErrorCode.INVALID_STATE,
                "Apple 로그인 서버 token lifecycle 설정이 완료되지 않았습니다.",
            )
        }
        return lifecycle.exchangeAndCapture(
            identity = identity,
            authorizationCode = authorizationCode,
            nonce = nonce,
        )
    }

    private fun <T> completeWithCaptureCompensation(
        capture: AppleCredentialCapture?,
        block: () -> T,
    ): T {
        try {
            return block()
        } catch (failure: Exception) {
            if (capture != null) {
                // Do not mask the original JWT/DB failure. The independently committed capture
                // also has a deadline if this immediate REQUIRES_NEW transition cannot commit.
                runCatching { appleTokenLifecycle.orElseThrow().abandonCapture(capture) }
            }
            throw failure
        }
    }

    private fun <T> inWriteTransaction(block: () -> T): T {
        val manager = transactionManager ?: return block()
        return requireNotNull(TransactionTemplate(manager).execute { block() })
    }

    private fun issueTokens(
        memberDto: MemberDto,
        appleCapture: AppleCredentialCapture? = null,
    ): MemberDto {
        val memberId = requireNotNull(memberDto.id) { "member.id가 없습니다." }
        val memberName = requireNotNull(memberDto.name) { "member.name이 없습니다." }
        // Explicit login은 member -> refresh row 순서로 잠근다. 활성 A session을 교체하면
        // 새 generation을 열고, logout이 이미 다음 빈 generation을 열었다면 그 값을 사용한다.
        // tokenLogin/refresh rotation은 이 경계를 호출하지 않아 같은 generation을 유지한다.
        val sessionGeneration = memberSessionFenceService.beginExplicitLoginSession(memberId)

        // 3) accessToken + refreshToken 발급
        val accessToken = jwtTokenProvider.createAccessToken(
            memberId,
            memberName,
            sessionGeneration,
        )
        val refreshToken = jwtTokenProvider.createRefreshToken(
            memberId,
            memberName,
            sessionGeneration,
        )

        // 4) refreshToken 저장 (기존 것들 정리하는 정책은 RefreshTokenService 내에서 처리)
        val refreshExpiry = jwtTokenProvider.getRefreshTokenExpiryLocalDateTime()
        refreshTokenService.saveNewToken(memberId, refreshToken, refreshExpiry)
        if (appleCapture != null) {
            // Binding shares the final member/session transaction. Any later rollback restores
            // CAPTURED, which remains independently durable for compensation.
            appleTokenLifecycle.orElseThrow().bindCapture(memberId, appleCapture)
        }

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
     * refresh-token logout.
     *
     * signed refresh JWT의 member/session generation과 DB의 현재 활성 refresh row가 모두
     * 일치할 때만 generation을 한 번 진행시키고 refresh/device token을 삭제한다. 응답 유실
     * replay나 이미 새 generation이 발급된 뒤 도착한 요청은 성공 no-op이다. access token은
     * 별도 blacklist가 아니라 generation mismatch로 즉시 무효화된다.
     */
    @Transactional
    fun logout(refreshToken: String) {
        // v4 이전 refresh JWT는 signed session-generation이 없어 일반 인증/재발급에는
        // fail-closed지만, 서명/issuer/만료/type과 DB 소유권이 유효하면 logout 정리는 허용한다.
        val logoutSession = jwtTokenProvider.resolveRefreshSessionForLogout(refreshToken)
            ?: return

        memberSessionFenceService.compareAndLogout(
            memberId = logoutSession.memberId,
            presentedSessionGeneration = logoutSession.sessionGeneration,
            presentedRefreshToken = refreshToken,
        )
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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun completeCuration(
        memberId: Long,
        presentedSessionGeneration: Long,
    ): Boolean {
        // security filter 통과 뒤 logout/re-login이 commit될 수 있다. 실제 member write와
        // 같은 transaction의 첫 mutation 경계에서 member row를 잠그고 signed generation을
        // 다시 확인해 이전 session이 curation 상태를 되살리지 못하게 한다.
        val member = memberService.getActiveMemberForUpdate(
            memberId,
            presentedSessionGeneration,
        )

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
        val tokenSessionGeneration = runCatching {
            jwtTokenProvider.getSessionGeneration(refreshToken)
        }.getOrElse {
            throw BusinessException(
                ErrorCode.INVALID_TOKEN,
                "세션 정보가 없는 리프레시 토큰입니다.",
            )
        }
        val currentSessionGeneration =
            memberService.getSessionGenerationForUpdate(memberIdFromToken)
        if (currentSessionGeneration != tokenSessionGeneration) {
            throw BusinessException(ErrorCode.INVALID_TOKEN, "종료된 로그인 세션입니다.")
        }

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
        val newAccessToken = jwtTokenProvider.createAccessToken(
            memberIdFromToken,
            memberName,
            currentSessionGeneration,
        )
        val newRefreshToken = jwtTokenProvider.createRefreshToken(
            memberIdFromToken,
            memberName,
            currentSessionGeneration,
        )
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
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun changePassword(
        memberId: Long,
        currentPassword: String,
        newPassword: String,
        presentedSessionGeneration: Long,
    ) {
        // password read/validation/write가 모두 같은 locked member snapshot을 사용한다.
        val member = memberService.getActiveMemberForUpdate(
            memberId,
            presentedSessionGeneration,
        )

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

    /**
     * access-authenticated destructive account withdrawal.
     *
     * logout과 달리 stale/replayed 요청을 성공으로 숨기지 않는다. security filter가 통과한 뒤
     * 요청이 지연될 수 있으므로 member row를 잠그고 presented access JWT generation을 다시
     * 비교한다. mismatch면 401로 fail closed하고 더 최신 session/account를 변경하지 않는다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun withdraw(
        memberId: Long,
        presentedSessionGeneration: Long,
        passwordForCheck: String?,
    ): MemberWithdrawalResult {
        // 1) Owner와 소유 일정의 모든 알림 참가자 member row를 전역 ID 순서로 잠근 뒤
        // 인증 generation을 검증한다. Owner만 먼저 잠그면 더 낮은 participant ID를 나중에
        // 얻는 withdrawal이 participant mutation과 lock order를 뒤집을 수 있다.
        val withdrawalFence = accountCleanupService.lockWithdrawalFence(
            memberId = memberId,
            presentedSessionGeneration = presentedSessionGeneration,
        )
        val member = withdrawalFence.member

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

        return completeWithdrawal(withdrawalFence, presentedSessionGeneration)
    }

    /**
     * Public web controllers must never call this boundary directly.
     *
     * The account-deletion core invokes it only after consuming a short-lived verification secret
     * and then a separate single-use deletion grant. The generation captured when verification was
     * initiated is still checked by lockWithdrawalFence, so any intervening login invalidates the
     * external proof instead of deleting a newer session.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun withdrawAfterExternalIdentityVerification(
        memberId: Long,
        presentedSessionGeneration: Long,
    ) {
        val withdrawalFence = accountCleanupService.lockWithdrawalFence(
            memberId = memberId,
            presentedSessionGeneration = presentedSessionGeneration,
        )
        completeWithdrawal(withdrawalFence, presentedSessionGeneration)
    }

    private fun completeWithdrawal(
        withdrawalFence: AccountWithdrawalFence,
        presentedSessionGeneration: Long,
    ): MemberWithdrawalResult {
        val member = withdrawalFence.member
        val id = requireNotNull(member.id) { "member.id 가 없습니다." }
        // Cleanup anonymizes this mutable entity. Freeze the provider decision before handing it
        // over so future cleanup changes cannot alter the response contract after deletion.
        val isAppleAccount = member.loginType == LoginType.APPLE

        // 위 member lock을 outer transaction 종료까지 유지한 상태에서 generation과 refresh
        // session만 먼저 닫는다. provider ownership row는 AccountCleanupService가
        // job -> source -> delivery/history -> device-token 순서의 마지막에 제거한다.
        memberSessionFenceService.invalidateSessionForWithdrawal(
            memberId = id,
            presentedSessionGeneration = presentedSessionGeneration,
        )
        val appleQueueResult = if (isAppleAccount) {
            // The queue write shares the account-cleanup transaction. A rollback therefore keeps
            // both the active account and active provider credential; commit triggers the first
            // provider attempt only after local cleanup is durable.
            appleTokenLifecycle.orElse(null)?.queueRevocation(id, member.snsId)
        } else {
            null
        }
        accountCleanupService.withdraw(withdrawalFence)
        memberService.updateMember(member)
        return MemberWithdrawalResult(
            manualAppleRevocationRequired =
                isAppleAccount &&
                    (appleQueueResult?.manualAppleRevocationRequired ?: true),
        )
    }



    // Legacy members may not have a profile row yet, so this read path can create
    // the default profile and must not run in a read-only transaction.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun getMyProfile(
        memberId: Long,
        presentedSessionGeneration: Long,
    ): MemberProfileDto {
        // GET이지만 legacy 회원에게 default profile을 생성할 수 있으므로 state-changing
        // boundary로 취급한다. profile이 이미 있어도 동일하게 fail-closed해 API 의미가
        // 데이터 존재 여부에 따라 달라지지 않게 한다.
        memberService.getActiveMemberForUpdate(memberId, presentedSessionGeneration)

        return memberProfileService.getByMemberId(memberId)
            ?: memberProfileService.createDefaultProfile(memberId)
    }

    /**
     * ✏️ 내 프로필 수정
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun updateMyProfile(
        memberId: Long,
        dto: MemberProfileDto,
        presentedSessionGeneration: Long,
    ): MemberProfileDto {
        memberService.getActiveMemberForUpdate(memberId, presentedSessionGeneration)

        // 여기서 nickname 길이, 금지어 등 검증을 Validator로 뺄 수도 있음
        return memberProfileService.updateProfile(memberId, dto)
    }
}

data class MemberWithdrawalResult(
    val manualAppleRevocationRequired: Boolean,
)
