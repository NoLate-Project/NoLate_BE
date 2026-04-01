package com.noLate.global.error

open class BusinessException(
    val errorCode : ErrorCode,
    override val message: String = errorCode.message
)  : RuntimeException(message)

class NotFoundException(
    errorCode : ErrorCode
) : BusinessException(errorCode)