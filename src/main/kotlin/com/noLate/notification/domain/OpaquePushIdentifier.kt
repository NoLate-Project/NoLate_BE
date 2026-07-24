package com.noLate.notification.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Push token/device id는 대소문자를 구분하는 opaque 값이다. DB의 기본 collation으로 원문을
 * 비교하거나 unique key에 싣지 않고, UTF-8 byte 기준 SHA-256만 identity/index로 사용한다.
 */
object OpaquePushIdentifier {
    fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
