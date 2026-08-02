package com.noLate.eta.resilience

import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock
import java.time.Duration

@Configuration
class EtaResilienceConfiguration {
    @Bean
    fun etaMonotonicTicker(): EtaMonotonicTicker = EtaMonotonicTicker.SYSTEM

    @Bean
    fun etaCalculationDeadline(
        @Value("\${eta.resilience.calculation-soft-deadline:8s}") defaultBudget: Duration,
        ticker: EtaMonotonicTicker,
    ): EtaCalculationDeadline = EtaCalculationDeadline(defaultBudget, ticker)

    @Bean
    fun etaProviderResiliencePolicyResolver(
        environment: Environment,
        @Value("\${eta.resilience.provider-default.max-concurrent-calls:8}")
        maxConcurrentCalls: Int,
        @Value("\${eta.resilience.provider-default.max-queued-calls:16}")
        maxQueuedCalls: Int,
        @Value("\${eta.resilience.provider-default.max-queue-wait:100ms}")
        maxQueueWait: Duration,
        @Value("\${eta.resilience.provider-default.failure-threshold:5}")
        failureThreshold: Int,
        @Value("\${eta.resilience.provider-default.open-duration:30s}")
        openDuration: Duration,
    ): EtaProviderResiliencePolicyResolver = EnvironmentEtaProviderResiliencePolicyResolver(
        environment = environment,
        defaultPolicy = EtaProviderResiliencePolicy(
            maxConcurrentCalls = maxConcurrentCalls,
            maxQueuedCalls = maxQueuedCalls,
            maxQueueWait = maxQueueWait,
            failureThreshold = failureThreshold,
            openDuration = openDuration,
        ),
    )

    @Bean
    fun etaProviderGuard(
        policyResolver: EtaProviderResiliencePolicyResolver,
        calculationDeadline: EtaCalculationDeadline,
        clocks: ObjectProvider<Clock>,
        ticker: EtaMonotonicTicker,
        observers: ObjectProvider<EtaProviderGuardObserver>,
    ): EtaProviderGuard = EtaProviderGuard(
        policyResolver = policyResolver,
        calculationDeadline = calculationDeadline,
        clock = clocks.getIfAvailable(Clock::systemUTC),
        ticker = ticker,
        observers = observers.orderedStream().toList(),
    )
}

private class EnvironmentEtaProviderResiliencePolicyResolver(
    private val environment: Environment,
    private val defaultPolicy: EtaProviderResiliencePolicy,
) : EtaProviderResiliencePolicyResolver {
    override fun resolve(providerId: String): EtaProviderResiliencePolicy {
        val canonical = canonicalEtaProviderId(providerId)
        val prefix = "eta.resilience.providers.$canonical"
        return EtaProviderResiliencePolicy(
            maxConcurrentCalls = environment.getProperty(
                "$prefix.max-concurrent-calls",
                Int::class.java,
                defaultPolicy.maxConcurrentCalls,
            ),
            maxQueuedCalls = environment.getProperty(
                "$prefix.max-queued-calls",
                Int::class.java,
                defaultPolicy.maxQueuedCalls,
            ),
            maxQueueWait = environment.getProperty(
                "$prefix.max-queue-wait",
                Duration::class.java,
                defaultPolicy.maxQueueWait,
            ),
            failureThreshold = environment.getProperty(
                "$prefix.failure-threshold",
                Int::class.java,
                defaultPolicy.failureThreshold,
            ),
            openDuration = environment.getProperty(
                "$prefix.open-duration",
                Duration::class.java,
                defaultPolicy.openDuration,
            ),
        )
    }
}
