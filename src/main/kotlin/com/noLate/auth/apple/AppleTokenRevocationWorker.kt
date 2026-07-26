package com.noLate.auth.apple

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.dao.ConcurrencyFailureException
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
import java.util.concurrent.RejectedExecutionException
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

/**
 * One row per REQUIRES_NEW transaction lets the caller continue after contention or quarantine.
 * A failed pessimistic lock must not poison a larger transaction containing later candidates.
 */
@Service
class AppleRevocationRowTransaction(
    private val repository: AppleProviderCredentialRepository,
    private val eventPublisher: org.springframework.context.ApplicationEventPublisher,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(id: Long, now: Instant, workerId: String): AppleRevocationLease? {
        val credential = repository.findByIdForUpdate(id) ?: return null
        if (
            credential.status == AppleProviderCredentialStatus.PENDING &&
            !credential.hasCompleteEnvelope()
        ) {
            credential.quarantineMalformed("MALFORMED_PENDING_ENVELOPE")
            repository.flush()
            return null
        }
        if (!credential.claim(workerId, now)) return null
        repository.flush()
        return credential.toLease(workerId)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recover(id: Long, staleBefore: Instant, now: Instant): Boolean {
        val credential = repository.findByIdForUpdate(id) ?: return false
        val changed = credential.recoverStale(staleBefore, now)
        if (changed) repository.flush()
        return changed
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun expireCapture(id: Long, now: Instant): Boolean {
        val credential = repository.findByIdForUpdate(id) ?: return false
        if (
            credential.status == AppleProviderCredentialStatus.CAPTURED &&
            !credential.hasCompleteEnvelope()
        ) {
            val changed = credential.quarantineMalformed("MALFORMED_CAPTURED_ENVELOPE")
            if (changed) repository.flush()
            return changed
        }
        val changed = credential.expireCapture(now)
        if (changed) {
            repository.flush()
            eventPublisher.publishEvent(AppleRevocationRequested())
        }
        return changed
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

@Service
class AppleTokenRevocationCoordinator(
    private val repository: AppleProviderCredentialRepository,
    private val rowTransaction: AppleRevocationRowTransaction,
) {
    fun claimNextDue(now: Instant, workerId: String, memberId: Long? = null): AppleRevocationLease? {
        // memberId is retained only for source compatibility with the original worker API. Wake
        // events are now globally coalesced, so every scan uses the same fair due ordering.
        @Suppress("UNUSED_VARIABLE")
        val ignoredMemberId = memberId
        val ids = repository.findDueIds(
            AppleProviderCredentialStatus.PENDING,
            now,
            PageRequest.of(0, CLAIM_SCAN_SIZE),
        )
        ids.forEach { id ->
            val lease = try {
                rowTransaction.claim(id, now, workerId)
            } catch (_: ConcurrencyFailureException) {
                // Another worker owns this candidate. Try the next due id instead of returning
                // empty and making one contended row stall the whole queue.
                null
            }
            if (lease != null) return lease
        }
        return null
    }

    fun promoteExpiredCaptures(now: Instant, batchSize: Int): Int {
        val ids = repository.findExpiredCaptureIds(
            AppleProviderCredentialStatus.CAPTURED,
            now,
            PageRequest.of(0, batchSize.coerceIn(1, 200)),
        )
        return ids.count { id ->
            try {
                rowTransaction.expireCapture(id, now)
            } catch (_: ConcurrencyFailureException) {
                false
            }
        }
    }

    fun recoverStale(now: Instant, staleBefore: Instant, batchSize: Int): Int {
        val ids = repository.findStaleIds(
            AppleProviderCredentialStatus.PROCESSING,
            staleBefore,
            PageRequest.of(0, batchSize.coerceIn(1, 200)),
        )
        return ids.count { id ->
            try {
                rowTransaction.recover(id, staleBefore, now)
            } catch (_: ConcurrencyFailureException) {
                false
            }
        }
    }

    fun complete(lease: AppleRevocationLease, now: Instant): Boolean =
        rowTransaction.complete(lease, now)

    fun retry(lease: AppleRevocationLease, nextAt: Instant, safeCode: String): Boolean =
        rowTransaction.retry(lease, nextAt, safeCode)

    fun block(lease: AppleRevocationLease, safeCode: String): Boolean =
        rowTransaction.block(lease, safeCode)

    fun blockedCount(): Long =
        repository.countByStatus(AppleProviderCredentialStatus.BLOCKED)

    private companion object {
        const val CLAIM_SCAN_SIZE = 50
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
        val expired = coordinator.promoteExpiredCaptures(now, boundedBatch)
        if (expired > 0) {
            log.warn("Promoted expired Apple credential captures. count={}", expired)
        }
        val recovered = coordinator.recoverStale(
            now = now,
            staleBefore = now.minusSeconds(properties.processingTimeoutSeconds.coerceAtLeast(10)),
            batchSize = boundedBatch,
        )
        if (recovered > 0) {
            log.warn("Recovered or quarantined stale Apple revocation leases. count={}", recovered)
        }
        val processed = drain(now, boundedBatch)
        val blocked = coordinator.blockedCount()
        if (blocked > 0) {
            // Stable structured field doubles as the operational backlog gauge when log metrics
            // are scraped; the runbook defines the SQL source-of-truth and alert threshold.
            log.warn("Apple revocation blocked backlog. count={}", blocked)
        }
        return processed
    }

    private fun drain(now: Instant, limit: Int): Int {
        var claimed = 0
        repeat(limit) {
            val lease = coordinator.claimNextDue(
                now = maxOf(now, Instant.now(clock)),
                workerId = workerId,
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
 * A private single-thread executor keeps Apple cleanup independent from all request threads.
 * Immediate signals are represented by two booleans, so duplicate events never create an
 * unbounded executor queue.
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
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private val wakeRequested = AtomicBoolean(false)
    private val wakeTaskScheduled = AtomicBoolean(false)
    @Volatile
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

    /**
     * Constant-time request-thread boundary: no DB read, decryption, network call, or wait.
     */
    fun wakeUp() {
        if (!running.get()) return
        wakeRequested.set(true)
        scheduleWakeTask()
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        wakeRequested.set(false)
        executor?.shutdownNow()
        executor = null
        wakeTaskScheduled.set(false)
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MAX_VALUE - 100

    private fun scheduleWakeTask() {
        if (!running.get() || !wakeTaskScheduled.compareAndSet(false, true)) return
        val scheduler = executor
        if (scheduler == null) {
            wakeTaskScheduled.set(false)
            return
        }
        try {
            scheduler.execute {
                try {
                    // At most two coalesced passes per task. Continued traffic schedules one
                    // trailing task rather than growing this executor's queue.
                    repeat(MAX_COALESCED_PASSES) {
                        wakeRequested.set(false)
                        runSafely()
                        if (!wakeRequested.get()) return@execute
                    }
                } finally {
                    wakeTaskScheduled.set(false)
                    if (running.get() && wakeRequested.get()) scheduleWakeTask()
                }
            }
        } catch (_: RejectedExecutionException) {
            wakeTaskScheduled.set(false)
            if (running.get()) {
                log.warn("Apple revocation wake signal rejected during executor transition.")
            }
        }
    }

    private fun runSafely() {
        if (!running.get()) return
        try {
            worker.runDue()
        } catch (failure: Exception) {
            log.warn(
                "Apple revocation scheduler iteration failed. failureType={}",
                failure.javaClass.simpleName,
            )
        }
    }

    private companion object {
        const val MAX_COALESCED_PASSES = 2
    }
}

/**
 * AFTER_COMMIT receives only a nonblocking wake signal. Provider I/O is impossible here because
 * the scheduler is the sole caller of worker.runDue().
 */
@Component
@ConditionalOnProperty(
    prefix = "auth.social.apple.token-lifecycle",
    name = ["enabled"],
    havingValue = "true",
)
class AppleRevocationWakeupListener(
    private val scheduler: AppleTokenRevocationScheduler,
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun afterCommit(@Suppress("UNUSED_PARAMETER") event: AppleRevocationRequested) {
        scheduler.wakeUp()
    }
}

private fun AppleProviderCredential.toLease(workerId: String): AppleRevocationLease {
    check(hasCompleteEnvelope()) { "Malformed Apple credential cannot become a provider lease." }
    return AppleRevocationLease(
        credentialId = requireNotNull(id),
        credentialKey = credentialKey,
        clientId = clientId,
        encryptionKeyId = requireNotNull(encryptionKeyId),
        initializationVector = requireNotNull(initializationVector),
        encryptedRefreshToken = requireNotNull(encryptedRefreshToken),
        attemptCount = attemptCount,
        workerId = workerId,
    )
}
