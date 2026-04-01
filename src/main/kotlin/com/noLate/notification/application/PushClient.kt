package com.noLate.notification.application

interface PushClient {

    /**
     * 실제 외부 Push Provider로 "단일 토큰"에 푸시 발송하는 책임
     */
    fun sendToToken(
        token: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap()
    )
}