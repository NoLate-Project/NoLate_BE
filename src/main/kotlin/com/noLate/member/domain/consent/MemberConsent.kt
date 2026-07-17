package com.noLate.member.domain.consent

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Entity
@Table(
    name = "member_consents",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_member_consents_member_type_version",
            columnNames = ["member_id", "consent_type", "document_version"],
        ),
    ],
    indexes = [
        Index(
            name = "idx_member_consents_member_agreed_at",
            columnList = "member_id, agreed_at",
        ),
    ],
)
class MemberConsent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "member_id", nullable = false)
    var memberId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false, length = 40)
    var consentType: MemberConsentType = MemberConsentType.SERVICE_TERMS,

    @Column(name = "document_version", nullable = false, length = 40)
    var documentVersion: String = "",

    @Column(name = "agreed_at", nullable = false)
    var agreedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "withdrawn_at")
    var withdrawnAt: LocalDateTime? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 30)
    var source: MemberConsentSource = MemberConsentSource.COMMON_SIGNUP,
)

enum class MemberConsentType {
    SERVICE_TERMS,
    PRIVACY_COLLECTION_USE,
}

enum class MemberConsentSource {
    COMMON_SIGNUP,
    SNS_SIGNUP,
}

data class SignupConsentCommand(
    val termsVersion: String,
    val privacyCollectionVersion: String,
    val termsAgreed: Boolean,
    val privacyCollectionAgreed: Boolean,
)
