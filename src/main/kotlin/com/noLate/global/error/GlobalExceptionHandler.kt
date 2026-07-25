package com.noLate.global.error

import com.noLate.global.common.ApiResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.slf4j.LoggerFactory
import org.springframework.validation.BindException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.ConcurrencyFailureException

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(ex: BusinessException): ResponseEntity<ApiResponse<Nothing>> {
        val status = when (ex.errorCode) {
            ErrorCode.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED
            ErrorCode.INVALID_TOKEN -> HttpStatus.UNAUTHORIZED
            ErrorCode.INVALID_CREDENTIALS -> HttpStatus.UNAUTHORIZED
            ErrorCode.FORBIDDEN -> HttpStatus.FORBIDDEN
            ErrorCode.FEATURE_DISABLED -> HttpStatus.FORBIDDEN
            ErrorCode.SUBSCRIPTION_LIMIT_EXCEEDED -> HttpStatus.FORBIDDEN
            ErrorCode.SUBSCRIPTION_POLICY_VIOLATION -> HttpStatus.FORBIDDEN
            ErrorCode.MEMBER_NOT_FOUND -> HttpStatus.NOT_FOUND
            ErrorCode.SCHEDULE_NOT_FOUND -> HttpStatus.NOT_FOUND
            ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND,
            ErrorCode.SCHEDULE_SHARE_NOT_FOUND,
            ErrorCode.SCHEDULE_CATEGORY_SHARE_NOT_FOUND,
            ErrorCode.SCHEDULE_SHARE_INVITATION_NOT_FOUND,
            ErrorCode.SCHEDULE_CALENDAR_NOT_FOUND,
            ErrorCode.SCHEDULE_CALENDAR_MEMBER_NOT_FOUND -> HttpStatus.NOT_FOUND
            ErrorCode.MEMBER_DUPLICATE_EMAIL,
            ErrorCode.DUPLICATE_EMAIL,
            ErrorCode.DUPLICATE_MEMBER -> HttpStatus.CONFLICT
            ErrorCode.ACCOUNT_LINK_REQUIRED -> HttpStatus.CONFLICT
            ErrorCode.INVALID_STATE,
            ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
            ErrorCode.SNS_SIGNUP_REQUIRED -> HttpStatus.CONFLICT
            else -> HttpStatus.BAD_REQUEST
        }

        val body = ApiResponse.failure<Nothing>(ex.message, ex.errorCode.code)
        return ResponseEntity.status(status).body(body)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(ex: ResponseStatusException): ResponseEntity<ApiResponse<Nothing>> {
        val body = ApiResponse.failure<Nothing>(
            errorMessage = ex.reason ?: "요청을 처리할 수 없습니다.",
            errorCode = "HTTP_${ex.statusCode.value()}",
        )
        return ResponseEntity.status(ex.statusCode).body(body)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class, BindException::class)
    fun handleValidationExceptions(ex: Exception): ResponseEntity<ApiResponse<Nothing>> {
        val errorMessage = when (ex) {
            is MethodArgumentNotValidException -> ex.bindingResult.allErrors.joinToString(", ") { it.defaultMessage ?: "Invalid input" }
            is BindException -> ex.bindingResult.allErrors.joinToString(", ") { it.defaultMessage ?: "Invalid input" }
            else -> "Invalid input"
        }

        val body = ApiResponse.failure<Nothing>(errorMessage, ErrorCode.INVALID_INPUT.code)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
        MissingServletRequestParameterException::class,
    )
    fun handleMalformedRequest(ex: Exception): ResponseEntity<ApiResponse<Nothing>> {
        // Jackson/Spring exception messages can echo the malformed request body. Push token
        // registration payloads therefore must never be attached as throwable/message logs.
        log.debug(
            "Malformed request rejected. failureType={}, rootType={}",
            ex.javaClass.simpleName,
            ex.rootCauseType(),
        )
        return ResponseEntity.badRequest().body(
            ApiResponse.failure(
                ErrorCode.INVALID_INPUT.message,
                ErrorCode.INVALID_INPUT.code,
            )
        )
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(ex: DataIntegrityViolationException): ResponseEntity<ApiResponse<Nothing>> {
        // Driver/Hibernate messages may contain SQL literals. P6Spy emits only value-free
        // operation/table/SQLState/vendor-code metadata; the HTTP boundary records class metadata only.
        log.warn(
            "Database uniqueness/constraint violation rejected. failureType={}, rootType={}",
            ex.javaClass.simpleName,
            ex.rootCauseType(),
        )
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiResponse.failure(
                "이미 사용 중인 계정 또는 데이터입니다.",
                ErrorCode.DUPLICATE_MEMBER.code,
            )
        )
    }

    @ExceptionHandler(ConcurrencyFailureException::class)
    fun handleException(ex: ConcurrencyFailureException): ResponseEntity<ApiResponse<Nothing>> {
        // @Version 충돌, deadlock victim, lock timeout은 서버 내부 오류가 아니다. 클라이언트가
        // 최신 상태를 다시 읽고 명시적으로 재시도할 수 있도록 하나의 409 계약으로 정규화한다.
        log.warn("Concurrent database modification rejected: {}", ex.javaClass.simpleName)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiResponse.failure(
                ErrorCode.CONCURRENT_MODIFICATION.message,
                ErrorCode.CONCURRENT_MODIFICATION.code,
            )
        )
    }

    @ExceptionHandler(AsyncRequestNotUsableException::class)
    fun handleDisconnectedClient(ex: AsyncRequestNotUsableException) {
        // 응답을 받을 클라이언트가 이미 연결을 끊었으므로 추가 응답을 작성하지 않는다.
        log.debug(
            "Client disconnected before the response completed. failureType={}",
            ex.javaClass.simpleName,
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleException(ex: Exception): ResponseEntity<ApiResponse<Nothing>> {
        // A generic persistence/provider exception can carry raw token/deviceId in its message
        // and therefore in every stack-frame rendering. Keep the operational classification
        // without attaching the throwable; subsystem boundaries log sanitized structured data.
        log.error(
            "Unhandled exception. failureType={}, rootType={}",
            ex.javaClass.simpleName,
            ex.rootCauseType(),
        )
        val body = ApiResponse.failure<Nothing>(
            ErrorCode.INTERNAL_SERVER_ERROR.message,
            ErrorCode.INTERNAL_SERVER_ERROR.code,
        )
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body)
    }
}

private fun Throwable.rootCauseType(): String {
    var current: Throwable = this
    val visited = mutableSetOf<Throwable>()
    while (current.cause != null && visited.add(current)) {
        current = current.cause ?: break
    }
    return current.javaClass.simpleName
}
