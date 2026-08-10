package com.noLate.subscription.application

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** Global advertising kill switch. Ads remain disabled unless operations explicitly enable them. */
@Component
@ConfigurationProperties("monetization.ads")
class AdvertisingProperties {
    var enabled: Boolean = false
}
