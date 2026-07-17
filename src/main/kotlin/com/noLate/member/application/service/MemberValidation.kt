package com.noLate.member.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.MemberDto
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import com.noLate.member.domain.member.ValidationMessage
import java.util.Locale

@Component
class MemberValidator(
    private val memberService: MemberService,
    private val passwordEncoder: PasswordEncoder
) {

    /**
     * COMMON 회원가입 가능한지 검증
     * - email 필수
     * - password 필수
     * - 같은 loginType + email 중복 불가
     */
    fun validateCommonSignUp(dto: MemberDto) {
        if (dto.loginType != LoginType.COMMON) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "회원가입은 COMMON 타입만 지원합니다.")
        }

        val email = normalizeEmail(dto.email)
            ?: throw BusinessException(ErrorCode.INVALID_INPUT, "COMMON 회원가입에는 email이 필요합니다.")
        dto.email = email
        val password = dto.password
            ?: throw BusinessException(ErrorCode.INVALID_INPUT, "COMMON 회원가입에는 password가 필요합니다.")
        validatePassword(password)
        validateName(dto.name)

        val exists = memberService.findByEmail(email)

        if (exists != null) {
            throw BusinessException(ErrorCode.ACCOUNT_LINK_REQUIRED)
        }
    }

    /**
     * COMMON 로그인 시
     * - email, password 필수
     * - loginType + email 로 회원 조회
     * - password 일치 여부 확인
     * -> 통과하면 Member 엔티티 리턴
     */
    fun validateAndGetMemberForCommonLogin(dto: MemberDto): MemberDto {
        val email = normalizeEmail(dto.email)
            ?: throw BusinessException(ErrorCode.INVALID_INPUT, "COMMON 로그인에는 email이 필요합니다.")
        dto.email = email
        val rawPassword = dto.password
            ?: throw BusinessException(ErrorCode.INVALID_INPUT, "COMMON 로그인에는 password가 필요합니다.")

        val found = memberService.findByEmailAndLoginType(email, LoginType.COMMON)
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND, "존재하지 않는 회원이거나 로그인 방식이 다릅니다.")


        val encoded = found.password
            ?: throw BusinessException(ErrorCode.INVALID_CREDENTIALS, "비밀번호가 설정되지 않은 계정입니다.")

        if (!passwordEncoder.matches(rawPassword, encoded)) {
            throw BusinessException(ErrorCode.INVALID_CREDENTIALS, "이메일 또는 비밀번호가 일치하지 않습니다.")
        }

        return found
    }

    fun validatePassword(password: String) {
        if (!Regex(ValidationMessage.PASSWORD_PATTERN).matches(password)) {
            throw BusinessException(ErrorCode.INVALID_INPUT, ValidationMessage.PASSWORD)
        }
    }

    fun validateName(name: String?) {
        val normalized = name?.trim()
        if (normalized.isNullOrBlank() || normalized.length > 50 || normalized.any(Char::isISOControl)) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "이름은 1~50자로 입력해 주세요.")
        }
    }

    private fun normalizeEmail(value: String?): String? {
        val normalized = value?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank) ?: return null
        if (normalized.length > 254 || !EMAIL_PATTERN.matches(normalized)) {
            throw BusinessException(ErrorCode.INVALID_INPUT, ValidationMessage.EMAIL)
        }
        return normalized
    }

    private companion object {
        val EMAIL_PATTERN = Regex("^[A-Za-z0-9.!#\\$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$")
    }
}
