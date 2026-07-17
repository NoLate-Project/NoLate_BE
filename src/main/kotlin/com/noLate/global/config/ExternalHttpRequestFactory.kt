package com.noLate.global.config

import org.springframework.http.client.SimpleClientHttpRequestFactory
import java.time.Duration

fun externalHttpRequestFactory(
    connectTimeout: Duration = Duration.ofSeconds(3),
    readTimeout: Duration = Duration.ofSeconds(8),
): SimpleClientHttpRequestFactory = SimpleClientHttpRequestFactory().apply {
    setConnectTimeout(connectTimeout)
    setReadTimeout(readTimeout)
}
