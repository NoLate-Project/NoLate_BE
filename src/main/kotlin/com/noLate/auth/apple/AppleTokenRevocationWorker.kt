package com.noLate.auth.apple

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AppleRevocationLease(
    val credentialId: Long,
    val credentialKey: String,
    val clientId: String,
    val encryptionKeyId: String,
    val initializationVector: String,
    val encryptedRefreshToken: String,
    val attemptCount: Int,
    val workerId: String,
)

@Service
class AppleTokenRevocationCoordinator(
    private val repository: AppleProviderCredentialRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claimNextDue(now: Instant, workerId: String, memberId: Long? = null): AppleRevocationLease? {
        val ids = if (memberId == null) {
            repository.findDueIds(
                AppleProviderCredentialStatus.PENDING,
                now,
                PageRequest.of(0, 1),
            )
        } else {
            repository.findDueIdsByMemberId(
                memberId,
                AppleProviderCredentialStatus.PENDING,
                now,
                PageRequest.of(0, 1),
            )
        }
        val credential = ids.singleOrNull()
            ?.let(repository::findByIdForUpdate)
            ?: return null
        if (!credential.claim(workerId, now)) return null
        repository.flush()
        return credential.toLease(workerId)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recoverStale(now: Instant, staleBefore: Instant, batchSize: Int): Int {
        val ids = repository.findStaleIds(
            AppleProviderCredentialStatus.PROCESSING,
            staleBefore,
            PageRequest.of(0, batchSize.coerceIn(1, 200)),
        )
        var recovered = 0
        ids.sorted().forEach { id ->
            val credential = repository.findByIdForUpdate(id) ?: return@forEach
            if (credential.recoverStale(staleBefore, now)) recovered += 1
        }
        repository.flush()
        return recovered
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun complete(lease: AppleRevocationLease, now: Instant): Boolean {
        val credential = ownedLease(lease) ?: return false
        val updated = credential.markRevoked(lease.workerId, now)
        repository.flush()
        return updated
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun retry(lease: AppleRevocationLease, nextAt: Instant, safeCode: String): Boolean {
        val credential = ownedLease(lease) ?: return false
        val updated = credential.retry(lease.workerId, nextAt, safeCode)
        repository.flush()
        return updated
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun block(lease: AppleRevocationLease, safeCode: String): Boolean {
        val credential = ownedLease(lease) ?: return false
        val updated = credential.block(lease.workerId, safeCode)
        repository.flush()
        return updated
    }

    private fun ownedLease(lease: AppleRevocationLease): AppleProviderCredential? {
        val credential = repository.findByIdForUpdate(lease.credentialId) ?: return null
        return credential.takeIf {
            it.status == AppleProviderCredentialStatus.PROCESSING &&
                it.lockedBy == lease.workerId &&
                it.attemptCount == lease.attemptCount
        }
    }
}

@Component
class AppleTokenRevocationWorker(
    private val properties: AppleTokenLifecycleProperties,
    private val coordinator: AppleTokenRevocationCoordinator,
    private val oauthClient: AppleOAuthClient,
    private val tokenCipher: AppleTokenCipher,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val workerId = "apple-revoke-${UUID.randomUUID()}"

    fun runDue() {
        runDue(Instant.now(clock))
    }

    fun runDue(now: Instant): Int {
        if (!properties.enabled || !properties.workerEnabled) return 0
        val boundedBatch = properties.batchSize.coerceIn(1, 200)
        val recovered = coordinator.recoverStale(
            now = now,
            staleBefore = now.minusSeconds(properties.processingTimeoutSeconds.coerceAtLeast(10)),
            batchSize = boundedBatch,
        )
        if (recovered > 0) {
            log.warn("Recovered stale Apple revocation leases. count={}", recovered)
        }
        return drain(now, boundedBatch, memberId = null)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun afterWithdrawal(event: AppleRevocationRequested) {
        if (!properties.enabled || !properties.workerEnabled) return
        try {
            drain(Instant.now(clock), properties.batchSize.coerceIn(1, 200), event.memberId)
        } catch (failure: Exception) {
            // Account cleanup is already committed. The durable PENDING row remains the source of
            // truth; only a value-free failure class is logged before the scheduled retry.
            log.warn(
                "Immediate Apple revocation drain failed after account cleanup. failureType={}",
                failure.javaClass.simpleName,
            )
        }
    }

    private fun drain(now: Instant, limit: Int, memberId: Long?): Int {
        var claimed = 0
        repeat(limit) {
            val lease = coordinator.claimNextDue(
                now = maxOf(now, Instant.now(clock)),
                workerId = workerId,
                memberId = memberId,
            ) ?: return claimed
            claimed += 1
            revoke(lease, maxOf(now, Instant.now(clock)))
        }
        return claimed
    }

    private fun revoke(lease: AppleRevocationLease, now: Instant) {
        if (lease.clientId != properties.clientId) {
            coordinator.block(lease, "APPLE_CLIENT_ID_MISMATCH")
            log.warn(
                "Apple revocation blocked. credentialId={}, attempt={}, reason={}",
                lease.credentialId,
                lease.attemptCount,
                "APPLE_CLIENT_ID_MISMATCH",
            )
            return
        }

        val refreshToken = try {
            tokenCipher.decrypt(
                credentialKey = lease.credentialKey,
                keyId = lease.encryptionKeyId,
                initializationVector = lease.initializationVector,
                ciphertext = lease.encryptedRefreshToken,
            )
        } catch (_: Exception) {
            coordinator.block(lease, "APPLE_TOKEN_DECRYPTION_FAILED")
            log.warn(
                "Apple revocation blocked. credentialId={}, attempt={}, reason={}",
                lease.credentialId,
                lease.attemptCount,
                "APPLE_TOKEN_DECRYPTION_FAILED",
            )
            return
        }

        try {
            oauthClient.revokeRefreshToken(refreshToken)
            val persisted = coordinator.complete(lease, maxOf(now, Instant.now(clock)))
            log.info(
                "Apple credential revocation confirmed. credentialId={}, attempt={}, persisted={}",
                lease.credentialId,
                lease.attemptCount,
                persisted,
            )
        } catch (failure: AppleProviderCallException) {
            transitionFailure(lease, now, failure.safeCode, failure.retryable)
        } catch (failure: Exception) {
            transitionFailure(
                lease,
                now,
                "APPLE_REVOKE_${failure.javaClass.simpleName}",
                retryable = true,
            )
        }
    }

    private fun transitionFailure(
        lease: AppleRevocationLease,
        now: Instant,
        safeCode: String,
        retryable: Boolean,
    ) {
        val exhausted = lease.attemptCount >= properties.maxAttempts.coerceAtLeast(1)
        val blocked = !retryable || exhausted
        val persisted = if (blocked) {
            coordinator.block(lease, safeCode)
        } else {
            coordinator.retry(
                lease = lease,
                nextAt = maxOf(now, Instant.now(clock)).plusSeconds(retryDelay(lease.attemptCount)),
                safeCode = safeCode,
            )
        }
        log.warn(
            "Apple revocation state updated. credentialId={}, attempt={}, blocked={}, " +
                "persisted={}, reason={}",
            lease.credentialId,
            lease.attemptCount,
            blocked,
            persisted,
            safeCode.sanitizedFailureCode(),
        )
    }

    private fun retryDelay(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 20)
        val multiplier = 1L shl exponent
        val base = properties.retryDelaySeconds.coerceAtLeast(1)
        val uncapped = runCatching { Math.multiplyExact(base, multiplier) }
            .getOrDefault(Long.MAX_VALUE)
        return minOf(uncapped, properties.maxRetryDelaySeconds.coerceAtLeast(base))
    }
}

/**
 * A private scheduler keeps Apple cleanup independent from the schedule-push migration gate.
 *
 * Adding another @EnableScheduling configuration would globally start every @Scheduled worker,
 * including push/outbox jobs that production intentionally keeps stopped during migrations.
 */
@Component
@ConditionalOnProperty(
    prefix = "auth.social.apple.token-lifecycle",
    name = ["enabled"],
    havingValue = "true",
)
class AppleTokenRevocationScheduler(
    private val properties: AppleTokenLifecycleProperties,
    private val worker: AppleTokenRevocationWorker,
) : SmartLifecycle {
    private val running = AtomicBoolean(false)
    private var executor: ScheduledExecutorService? = null

    override fun start() {
        if (!properties.workerEnabled || !running.compareAndSet(false, true)) return
        val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "apple-token-revocation").apply { isDaemon = true }
        }
        executor = scheduler
        scheduler.scheduleWithFixedDelay(
            { runSafely() },
            properties.fixedDelayMillis,
            properties.fixedDelayMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        executor?.shutdownNow()
        executor = null
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MAX_VALUE - 100

    private fun runSafely() {
        if (!running.get()) return
        try {
            worker.runDue()
        } catch (failure: Exception) {
            LoggerFactory.getLogger(javaClass).warn(
                "Apple revocation scheduler iteration failed. failureType={}",
                failure.javaClass.simpleName,
            )
        }
    }
}

private fun AppleProviderCredential.toLease(workerId: String): AppleRevocationLease =
    AppleRevocationLease(
        credentialId = requireNotNull(id),
        credentialKey = credentialKey,
        clientId = clientId,
        encryptionKeyId = requireNotNull(encryptionKeyId),
        initializationVector = requireNotNull(initializationVector),
        encryptedRefreshToken = requireNotNull(encryptedRefreshToken),
        attemptCount = attemptCount,
        workerId = workerId,
    )
