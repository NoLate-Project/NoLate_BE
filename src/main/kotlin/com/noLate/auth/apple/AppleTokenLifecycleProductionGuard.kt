package com.noLate.auth.apple

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.net.URI

@Component
@Profile("prod")
class AppleTokenLifecycleProductionGuard(
    private val properties: AppleTokenLifecycleProperties,
    private val clientSecretSigner: AppleClientSecretSigner,
    private val tokenCipher: AppleTokenCipher,
    @Value("\${auth.social.apple.audiences:}")
    appleAudiences: String,
) : SmartInitializingSingleton {
    private val allowedAudiences = appleAudiences.split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()

    override fun afterSingletonsInstantiated() {
        try {
            properties.requireReady()
            check(properties.workerEnabled) {
                "Apple revocation worker must be enabled in production."
            }
            check(properties.clientId in allowedAudiences) {
                "Apple token client-id must be included in the verified identity-token audiences."
            }
            val provider = URI(properties.baseUrl.trim())
            check(
                provider.scheme == "https" &&
                    provider.host == "appleid.apple.com" &&
                    provider.port == -1 &&
                    provider.path.trimEnd('/').isEmpty()
            ) {
                "Production Apple provider base-url must be https://appleid.apple.com."
            }
            clientSecretSigner.validateKey()
            tokenCipher.validateKeys()
        } catch (failure: IllegalStateException) {
            // The message is intentionally limited to configuration field names; key material and
            // parse exceptions are never attached to the startup failure.
            throw IllegalStateException(
                "Production startup blocked: ${failure.message}",
            )
        } catch (_: Exception) {
            throw IllegalStateException(
                "Production startup blocked: Apple token lifecycle secret material is invalid.",
            )
        }
    }
}
