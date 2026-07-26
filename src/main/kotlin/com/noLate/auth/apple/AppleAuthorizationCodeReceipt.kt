package com.noLate.auth.apple

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * Immutable, plaintext-free evidence that an Apple five-minute/single-use code was reserved.
 *
 * It contains only one-way code/subject fingerprints plus configuration metadata. Reservation
 * commits before provider I/O. Rows are never updated or replaced, including when Apple returns
 * the same long-lived refresh token for multiple fresh authorization codes.
 */
@Entity
@Table(
    name = "apple_authorization_code_receipts",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_apple_authorization_receipts_receipt_key",
            columnNames = ["receipt_key"],
        ),
        UniqueConstraint(
            name = "uk_apple_authorization_receipts_code_hash",
            columnNames = ["authorization_code_hash"],
        ),
    ],
)
class AppleAuthorizationCodeReceipt(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "receipt_key", nullable = false, length = 36, updatable = false)
    val receiptKey: String = UUID.randomUUID().toString(),

    @Column(name = "authorization_code_hash", nullable = false, length = 64, updatable = false)
    val authorizationCodeHash: String = "",

    @Column(name = "expected_subject_hash", nullable = false, length = 64, updatable = false)
    val expectedSubjectHash: String = "",

    @Column(name = "client_id", nullable = false, length = 255, updatable = false)
    val clientId: String = "",

    @Column(name = "reserved_at", nullable = false, updatable = false)
    val reservedAt: Instant = Instant.EPOCH,
)
