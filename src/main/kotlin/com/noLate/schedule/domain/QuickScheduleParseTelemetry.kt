package com.noLate.schedule.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

/**
 * 빠른 일정 신뢰도 보정에 필요한 최소 데이터만 보관한다.
 *
 * 원문, 인식 텍스트, 제목, 메모, 장소명과 좌표는 의도적으로 저장하지 않는다.
 */
@Entity
@Table(
    name = "quick_schedule_parse_telemetry",
    indexes = [
        Index(name = "idx_quick_parse_member", columnList = "member_id, created_at"),
        Index(name = "idx_quick_parse_calibration", columnList = "input_type, confidence_level, outcome, created_at"),
        Index(name = "idx_quick_parse_expiry", columnList = "expires_at"),
    ],
)
class QuickScheduleParseTelemetry(
    @Id
    @Column(name = "analysis_id", length = 36, nullable = false)
    val analysisId: String,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "input_type", length = 30, nullable = false)
    val inputType: ScheduleParseInputType,

    @Enumerated(EnumType.STRING)
    @Column(name = "client_platform", length = 20, nullable = false)
    val clientPlatform: QuickScheduleClientPlatform,

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_source", length = 30, nullable = false)
    val parseSource: ScheduleParseSource,

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence_level", length = 20)
    val confidenceLevel: ScheduleParseConfidenceLevel?,

    @Column(name = "overall_confidence")
    val overallConfidence: Double?,

    @Column(name = "recognition_confidence")
    val recognitionConfidence: Double?,

    @Column(name = "date_confidence")
    val dateConfidence: Double?,

    @Column(name = "time_confidence")
    val timeConfidence: Double?,

    @Column(name = "destination_confidence")
    val destinationConfidence: Double?,

    @Column(name = "needs_review", nullable = false)
    val needsReview: Boolean,

    @Column(name = "confidence_version", length = 40, nullable = false)
    val confidenceVersion: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 20, nullable = false)
    var outcome: QuickScheduleFeedbackOutcome = QuickScheduleFeedbackOutcome.PENDING,

    @Enumerated(EnumType.STRING)
    @Column(name = "date_verification", length = 30, nullable = false)
    var dateVerification: QuickScheduleVerificationSignal = QuickScheduleVerificationSignal.UNTOUCHED,

    @Enumerated(EnumType.STRING)
    @Column(name = "time_verification", length = 30, nullable = false)
    var timeVerification: QuickScheduleVerificationSignal = QuickScheduleVerificationSignal.UNTOUCHED,

    @Enumerated(EnumType.STRING)
    @Column(name = "destination_verification", length = 30, nullable = false)
    var destinationVerification: QuickScheduleVerificationSignal = QuickScheduleVerificationSignal.UNTOUCHED,

    @Column(name = "global_confirmed", nullable = false)
    var globalConfirmed: Boolean = false,

    @Column(name = "feedback_at")
    var feedbackAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
) {
    protected constructor() : this(
        analysisId = "00000000-0000-0000-0000-000000000000",
        memberId = 1,
        inputType = ScheduleParseInputType.TEXT,
        clientPlatform = QuickScheduleClientPlatform.UNKNOWN,
        parseSource = ScheduleParseSource.RULE,
        confidenceLevel = null,
        overallConfidence = null,
        recognitionConfidence = null,
        dateConfidence = null,
        timeConfidence = null,
        destinationConfidence = null,
        needsReview = true,
        confidenceVersion = "unknown",
        createdAt = Instant.EPOCH,
        expiresAt = Instant.EPOCH,
    )

    fun applyFeedback(feedback: QuickScheduleParseFeedbackDto, now: Instant) {
        // 저장 성공은 취소보다 강한 최종 신호다. 늦게 도착한 취소 이벤트로 되돌리지 않는다.
        if (outcome == QuickScheduleFeedbackOutcome.SAVED &&
            feedback.outcome == QuickScheduleFeedbackOutcome.CANCELLED
        ) return
        require(feedback.outcome != QuickScheduleFeedbackOutcome.PENDING)
        outcome = feedback.outcome
        dateVerification = feedback.date
        timeVerification = feedback.time
        destinationVerification = feedback.destination
        globalConfirmed = feedback.globalConfirmed
        feedbackAt = now
    }
}
