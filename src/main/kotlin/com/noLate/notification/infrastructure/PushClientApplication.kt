package com.noLate.notification.infrastructure

import com.noLate.notification.application.PushClient
import com.noLate.notification.application.PushSendResult
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "firebase", name = ["enabled"], havingValue = "false", matchIfMissing = true)
class PushClientApplication : PushClient {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun sendToToken(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>
    ): PushSendResult {
        log.info(
            "[DUMMY PUSH] token={}, title={}, body={}, data={}",
            token, title, body, data
        )
        return PushSendResult(messageId = "dummy")
    }
}
