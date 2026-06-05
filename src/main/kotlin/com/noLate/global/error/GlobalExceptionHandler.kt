package com.noLate.global.error

import com.noLate.global.common.ApiResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.BindException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(ex: BusinessException): ResponseEntity<ApiResponse<Nothing>> {
        val status = when (ex.errorCode) {
            ErrorCode.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED
            ErrorCode.FORBIDDEN -> HttpStatus.FORBIDDEN
            ErrorCode.MEMBER_NOT_FOUND -> HttpStatus.NOT_FOUND
            ErrorCode.SCHEDULE_NOT_FOUND -> HttpStatus.NOT_FOUND
            ErrorCode.MEMBER_DUPLICATE_EMAIL -> HttpStatus.BAD_REQUEST
            else -> HttpStatus.BAD_REQUEST
        }

        val body = ApiResponse.failure<Nothing>( ex.message)
        return ResponseEntity.status(status).body(body)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class, BindException::class)
    fun handleValidationExceptions(ex: Exception): ResponseEntity<ApiResponse<Nothing>> {
        val errorMessage = when (ex) {
            is MethodArgumentNotValidException -> ex.bindingResult.allErrors.joinToString(", ") { it.defaultMessage ?: "Invalid input" }
            is BindException -> ex.bindingResult.allErrors.joinToString(", ") { it.defaultMessage ?: "Invalid input" }
            else -> "Invalid input"
        }

        val body = ApiResponse.failure<Nothing>(errorMessage)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    @ExceptionHandler(Exception::class)
    fun handleException(ex: Exception): ResponseEntity<ApiResponse<Nothing>> {
        val body = ApiResponse.failure<Nothing>(ErrorCode.INTERNAL_SERVER_ERROR.message)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body)
    }
}
