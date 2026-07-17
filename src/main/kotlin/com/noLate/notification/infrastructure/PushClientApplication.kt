package com.noLate.notification.infrastructure

import com.noLate.notification.application.PushClient
import com.noLate.notification.application.PushSendResult
import com.noLate.notification.application.PushProviderUnavailableException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "firebase", name = ["enabled"], havingValue = "false", matchIfMissing = true)
class PushClientApplication : PushClient {
    override fun sendToToken(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>
    ): PushSendResult {
        // 공급자가 꺼진 환경에서도 실제 전송하지 않은 메시지를 성공으로 기록하지 않는다.
        // token/title/body/data는 민감할 수 있어 로그에도 남기지 않는다.
        throw PushProviderUnavailableException()
    }
}
