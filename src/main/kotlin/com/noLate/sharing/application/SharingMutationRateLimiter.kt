package com.noLate.sharing.application

enum class SharingMutationScope {
    DIRECT_SHARE,
    INVITATION_CREATE,
}

fun interface SharingMutationRateLimiter {
    fun requirePermit(memberId: Long, scope: SharingMutationScope)
}
