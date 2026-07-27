package com.noLate.notification.domain

import java.util.UUID

object PushLogicalEventKey {
    fun deterministic(memberId: Long, deduplicationKey: String): String =
        "key:${OpaquePushIdentifier.fingerprint("$memberId:$deduplicationKey")}"

    fun newEvent(): String = "event:${UUID.randomUUID()}"
}

fun Map<String, String>.withPushAccountBinding(
    logicalEventKey: String,
    recipientMemberId: Long,
): Map<String, String> =
    this + mapOf(
        "logicalEventKey" to logicalEventKey,
        "recipientMemberId" to recipientMemberId.toString(),
    )
