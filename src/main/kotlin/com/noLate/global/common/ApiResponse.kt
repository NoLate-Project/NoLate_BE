package com.noLate.global.common

class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val errorMessage: String? = null,
    val errorCode: String? = null,
) {
    companion object {
        fun <T> success(data: T): ApiResponse<T> {
            return ApiResponse(success = true, data = data)
        }

        fun <T> failure(errorMessage: String, errorCode: String? = null): ApiResponse<T> {
            return ApiResponse(success = false, errorMessage = errorMessage, errorCode = errorCode)
        }
    }
}

data class ErrorResponse(
    val success: Boolean = false,
    val errorMessage: String
)
