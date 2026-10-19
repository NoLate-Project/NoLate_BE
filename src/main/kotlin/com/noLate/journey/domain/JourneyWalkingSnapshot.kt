package com.noLate.journey.domain

import jakarta.persistence.*

/** One short-lived server path per owner/schedule. Not an admission/vehicle/GPS receipt. */
@Entity
@Table(name = "journey_walking_snapshots", uniqueConstraints = [
    UniqueConstraint(name = "uk_walking_owner_schedule", columnNames = ["member_id", "schedule_id"]),
    UniqueConstraint(name = "uk_walking_snapshot_id", columnNames = ["snapshot_id"]),
], indexes = [Index(name = "idx_walking_expiry", columnList = "expires_at_ms")])
class JourneyWalkingSnapshot(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
    @Version var version: Long = 0,
    @Column(name = "member_id", nullable = false) val memberId: Long,
    @Column(name = "schedule_id", nullable = false) val scheduleId: Long,
    @Column(name = "snapshot_id", nullable = false, length = 36) var snapshotId: String,
    @Column(name = "expires_at_ms", nullable = false) var expiresAtMs: Long,
    @Column(name = "contract_json", nullable = false, columnDefinition = "LONGTEXT") var contractJson: String,
)
