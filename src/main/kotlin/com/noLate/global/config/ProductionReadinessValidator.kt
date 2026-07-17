package com.noLate.global.config

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** 필수 운영 공급자가 반쪽 설정인 상태로 애플리케이션이 기동되는 것을 막는다. */
@Component
@Profile("prod")
class ProductionReadinessValidator(
    @Value("\${routing.tmap.enabled:false}") private val tmapEnabled: Boolean,
    @Value("\${routing.tmap.app-key:}") private val tmapAppKey: String,
    @Value("\${firebase.enabled:false}") private val firebaseEnabled: Boolean,
    @Value("\${auth.social.kakao.app-id:}") private val kakaoAppId: String,
    @Value("\${auth.social.apple.audiences:}") private val appleAudiences: String,
) {
    @PostConstruct
    fun validate() {
        require(tmapEnabled && tmapAppKey.isNotBlank()) { "Production TMAP configuration is required." }
        require(firebaseEnabled) { "Production Firebase push configuration is required." }
        require(kakaoAppId.isNotBlank()) { "Production Kakao app id is required." }
        require(appleAudiences.split(',').any { it.trim().isNotBlank() }) {
            "Production Apple audience configuration is required."
        }
    }
}
