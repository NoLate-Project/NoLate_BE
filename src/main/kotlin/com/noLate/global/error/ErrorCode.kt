package com.noLate.global.error

enum class ErrorCode(
    val code: String,
    val message: String
) {
    // 공통
    INTERNAL_SERVER_ERROR("C000", "서버 에러가 발생했습니다."),
    INVALID_INPUT("C001", "요청 값이 올바르지 않습니다."),
    INVALID_STATE("C002" ,"상태가 올바르지 않습니다."),
    CONCURRENT_MODIFICATION("C003", "다른 요청에서 먼저 변경했습니다. 새로고침 후 다시 시도해 주세요."),
    UNAUTHORIZED("A001", "인증이 필요합니다."),
    FORBIDDEN("A002", "권한이 없습니다."),

    // Member 영역
    MEMBER_NOT_FOUND("M001", "회원이 존재하지 않습니다."),
    MEMBER_NOT_FOUND_NAME("M003", "회원의 이름을 찾을수 없습니다."),
    MEMBER_DUPLICATE_EMAIL("M002", "이미 사용 중인 이메일입니다."),
    INVALID_CREDENTIALS("A003", "이메일 또는 비밀번호가 일치하지 않습니다."),
    DUPLICATE_EMAIL("M008", "이미 가입된 이메일입니다."),
    DUPLICATE_MEMBER("M009", "이미 가입된 회원입니다."),
    SNS_SIGNUP_REQUIRED("M004", "SNS 신규 가입 동의가 필요합니다."),
    CONSENT_REQUIRED("M005", "회원가입 필수 동의가 필요합니다."),
    CONSENT_VERSION_MISMATCH("M006", "최신 약관 확인이 필요합니다."),
    ACCOUNT_LINK_REQUIRED("M007", "같은 이메일의 기존 계정이 있습니다. 기존 로그인 방식으로 로그인해 주세요."),

    // Token 영역
    INVALID_TOKEN("T001", "잘못된 토큰 값입니다."),

    // Notification 영역
    NOTIFICATION_NOT_FOUND("N001", "알림이 존재하지 않습니다."),

    // Schedule 영역
    SCHEDULE_NOT_FOUND("S001", "일정이 존재하지 않습니다."),
    SUBSCRIPTION_LIMIT_EXCEEDED("S002", "요금제 사용 한도를 초과했습니다."),
    SUBSCRIPTION_POLICY_VIOLATION("S003", "현재 요금제에서 사용할 수 없는 설정입니다."),
    SCHEDULE_CATEGORY_NOT_FOUND("S004", "일정 카테고리가 존재하지 않습니다."),
    SCHEDULE_SHARE_NOT_FOUND("S005", "일정 공유 정보가 존재하지 않습니다."),
    SCHEDULE_CATEGORY_SHARE_NOT_FOUND("S006", "일정 카테고리 공유 정보가 존재하지 않습니다."),
    SCHEDULE_SHARE_INVITATION_NOT_FOUND("S007", "일정 공유 초대가 존재하지 않습니다."),
    SCHEDULE_TRAVEL_PLAN_NOT_FOUND("S008", "개인 이동 계획이 존재하지 않습니다."),
    SCHEDULE_CALENDAR_NOT_FOUND("S009", "공유 캘린더가 존재하지 않습니다."),
    SCHEDULE_CALENDAR_MEMBER_NOT_FOUND("S010", "공유 캘린더 멤버가 존재하지 않습니다."),

    // Favorite Place 영역
    FAVORITE_PLACE_CATEGORY_NOT_FOUND("F001", "즐겨찾기 장소 카테고리가 존재하지 않습니다."),
    FAVORITE_PLACE_NOT_FOUND("F002", "즐겨찾기 장소가 존재하지 않습니다."),

    // Recent Route Place 영역
    RECENT_ROUTE_PLACE_NOT_FOUND("R001", "최근 검색 장소가 존재하지 않습니다.")
}
