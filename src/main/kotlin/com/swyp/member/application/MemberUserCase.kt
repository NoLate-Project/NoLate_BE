package com.swyp.member.application

import com.swyp.global.security.JwtTokenProvider
import com.swyp.member.domain.Member.LoginType
import com.swyp.member.domain.Member.Member
import com.swyp.member.domain.Member.MemberDto
import com.swyp.member.domain.MemberSetting.MemberSettingDto
import jakarta.transaction.Transactional
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

@Component
class MemberUseCase(
    private val memberService: MemberService,
    private val memberSettingService: MemberSettingService,
    private val jwtTokenProvider: JwtTokenProvider,
    private val passwordEncoder: PasswordEncoder,
    private val memberValidator: MemberValidator,
) {

    @Transactional
    fun signUp(memberDto: MemberDto): MemberDto {
        // COMMON 회원가입 검증 (이메일, 비번, 중복 등)
            memberValidator.validateCommonSignUp(memberDto)

        // 비밀번호 암호화
        val encodedPassword = passwordEncoder.encode(memberDto.password!!)

        // DTO → 엔티티 변환 + 암호화된 비번, 로그인타입 세팅
        val entity = memberDto.toEntity().apply {
            this.password = encodedPassword
            this.loginType = LoginType.COMMON
        }

        // Service가 DTO를 리턴한다고 가정
        val savedMemberDto: MemberDto = memberService.addMember(entity)

        // 기본 설정 생성
        memberSettingService.createDefaultSetting(
            MemberSettingDto().apply { memberId = requireNotNull(savedMemberDto.id) }
        )

        // 저장된 DTO 그대로 반환
        return savedMemberDto
    }

    @Transactional
    fun login(requestDto: MemberDto): MemberDto {

        // 1) loginType에 따라 로그인 대상 MemberDto 확보
        val memberDto: MemberDto = when (requestDto.loginType) {

            LoginType.COMMON -> {
                // 검증 + 조회 + 비밀번호 체크까지 한 뒤, 로그인 성공한 MemberDto 리턴
                memberValidator.validateAndGetMemberForCommonLogin(requestDto)
            }

            else -> {
                val snsId = memberValidator.requireSnsId(requestDto)

                var found: MemberDto? =
                    memberService.findByLoginTypeAndSnsId(requestDto.loginType, snsId)

                if (found == null) {
                    val name = requestDto.name ?: requestDto.email ?: "사용자"

                    // SNS 신규 회원 생성 (파라미터는 엔티티, 리턴은 DTO라고 가정)
                    found = memberService.addMember(
                        Member().apply {
                            this.snsId = snsId
                            this.name = name
                            this.loginType = requestDto.loginType
                            this.email = requestDto.email
                        }
                    )

                    // 기본 설정 생성
                    memberSettingService.createDefaultSetting(
                        MemberSettingDto().apply { memberId = requireNotNull(found.id) }
                    )
                }

                found
            }
        } ?: throw IllegalStateException("로그인 과정에서 memberDto가 null입니다.")

        // 2) 공통 토큰 로직 (DTO 기준)
        val memberId = requireNotNull(memberDto.id) { "member.id가 없습니다." }
        val memberName = requireNotNull(memberDto.name) { "member.name이 없습니다." }

        val accessToken = jwtTokenProvider.createAccessToken(memberId, memberName)
        val refreshToken = jwtTokenProvider.createRefreshToken(memberId, memberName)

        // 3) 토큰 세팅 후 DTO 반환
        return memberDto.apply {
            this.accessToken = accessToken
            this.refreshToken = refreshToken
        }
    }

    fun jwtLogin(memberDto : MemberDto) : MemberDto {

        jwtTokenProvider.getMemberIdFromToken(memberDto.accessToken || memberDto.refreshToken)
    }
}