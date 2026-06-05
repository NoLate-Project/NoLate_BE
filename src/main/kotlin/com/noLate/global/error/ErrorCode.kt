package com.noLate.global.error

enum class ErrorCode(
    val code: String,
    val message: String
) {
    // 공통
    INTERNAL_SERVER_ERROR("C000", "서버 에러가 발생했습니다."),
    INVALID_INPUT("C001", "요청 값이 올바르지 않습니다."),
    INVALID_STATE("C002" ,"상태가 올바르지 않습니다."),
    UNAUTHORIZED("A001", "인증이 필요합니다."),
    FORBIDDEN("A002", "권한이 없습니다."),

    // Member 영역
    MEMBER_NOT_FOUND("M001", "회원이 존재하지 않습니다."),
    MEMBER_NOT_FOUND_NAME("M003", "회원의 이름을 찾을수 없습니다."),
    MEMBER_DUPLICATE_EMAIL("M002", "이미 사용 중인 이메일입니다."),
    INVALID_CREDENTIALS("A003", "이메일 또는 비밀번호가 일치하지 않습니다."),
    DUPLICATE_EMAIL("A002", "이미 가입된 이메일입니다."),
    DUPLICATE_MEMBER("A003", "이미 COMMON 방식으로 가입된 이메일입니다."),

    // Token 영역
    INVALID_TOKEN("T001", "잘못된 토큰 값입니다."),

    // Schedule 영역
    SCHEDULE_NOT_FOUND("S001", "일정이 존재하지 않습니다.")
}
