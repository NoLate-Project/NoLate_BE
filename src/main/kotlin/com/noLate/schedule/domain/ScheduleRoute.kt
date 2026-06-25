package com.noLate.schedule.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Lob
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import org.hibernate.annotations.Comment
import java.time.Instant

/**
 * 일정의 이동 계획과 선택된 경로 상세를 저장하는 1:1 하위 엔티티.
 *
 * 일정이 장소 이동을 필요로 할 때만 생성된다. 출발 알림 계산, 경로 재표시,
 * 출발지/도착지 복원처럼 NoLate 특화 기능에 필요한 값들을 한곳에 모은다.
 */
@Entity
@Table(name = "schedule_routes")
@Comment("일정 이동 계획과 경로 상세 테이블")
class ScheduleRoute(
    /** 일정 경로 PK */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("일정 경로 PK")
    var id: Long? = null,

    /** 연결된 일정 */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false, unique = true)
    @Comment("연결된 일정 id")
    var schedule: Schedule? = null,

    /** 일정 시작 전 이동에 필요한 예상 시간(분) */
    @Column(name = "travel_minutes")
    @Comment("이동 예상 시간(분)")
    var travelMinutes: Int? = null,

    /** 계산되었거나 사용자가 확정한 출발 시각 */
    @Column(name = "depart_at")
    @Comment("출발 예정 시각")
    var departAt: Instant? = null,

    /** 사용자가 출발 완료 처리한 시각 */
    @Column(name = "departed_at")
    @Comment("출발 완료 처리 시각")
    var departedAt: Instant? = null,

    /** 이동 수단 */
    @Enumerated(EnumType.STRING)
    @Column(name = "travel_mode", length = 20)
    @Comment("이동 수단")
    var travelMode: ScheduleTravelMode? = null,

    /** 화면에 표시할 장소 또는 경로 요약명 */
    @Column(name = "location_name", length = 255)
    @Comment("장소 또는 경로 요약명")
    var locationName: String? = null,

    /** 출발지 이름 */
    @Column(name = "origin_name", length = 255)
    @Comment("출발지 이름")
    var originName: String? = null,

    /** 출발지 주소 */
    @Column(name = "origin_address", length = 500)
    @Comment("출발지 주소")
    var originAddress: String? = null,

    /** 출발지 위도 */
    @Column(name = "origin_lat")
    @Comment("출발지 위도")
    var originLat: Double? = null,

    /** 출발지 경도 */
    @Column(name = "origin_lng")
    @Comment("출발지 경도")
    var originLng: Double? = null,

    /** 도착지 이름 */
    @Column(name = "destination_name", length = 255)
    @Comment("도착지 이름")
    var destinationName: String? = null,

    /** 도착지 주소 */
    @Column(name = "destination_address", length = 500)
    @Comment("도착지 주소")
    var destinationAddress: String? = null,

    /** 도착지 위도 */
    @Column(name = "destination_lat")
    @Comment("도착지 위도")
    var destinationLat: Double? = null,

    /** 도착지 경도 */
    @Column(name = "destination_lng")
    @Comment("도착지 경도")
    var destinationLng: Double? = null,

    /** 선택한 경로 상세 JSON(path, leg, 요금 등) */
    @Lob
    @Column(name = "route_json", columnDefinition = "LONGTEXT")
    @Comment("선택한 경로 상세 JSON")
    var routeJson: String? = null,

    /** 실시간 ETA 기반 출발 알림 활성화 여부 */
    @Column(
        name = "notification_enabled",
        nullable = false,
        columnDefinition = "boolean default false",
    )
    @Comment("실시간 출발 알림 활성화 여부")
    var notificationEnabled: Boolean = false,

    /** 권장 출발 시각 기준 몇 분 전부터 알림을 시작할지 */
    @Column(name = "notification_lead_minutes")
    @Comment("알림 시작 시점(분)")
    var notificationLeadMinutes: Int? = null,

    /** 사용자에게 재알림을 보내는 최소 간격 */
    @Column(name = "notification_interval_minutes")
    @Comment("재알림 간격(분)")
    var notificationIntervalMinutes: Int? = null,
)
