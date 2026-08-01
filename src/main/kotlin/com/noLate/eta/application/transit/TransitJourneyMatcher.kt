package com.noLate.eta.application.transit

import com.noLate.eta.domain.SelectedTransitRoute
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitRideSignature
import com.noLate.eta.domain.TransitServiceClass
import org.springframework.stereotype.Component

/**
 * 재조회 후보의 응답 순번이나 ETA가 아니라 노선·방향·승하차 정류장으로 선택 경로를 찾는다.
 * 일치 후보가 없을 때 가장 빠른 다른 경로를 반환하지 않는 것이 사용자 선택 보존 규칙이다.
 */
@Component
class TransitJourneyMatcher {
    fun findSelected(
        selected: SelectedTransitRoute,
        candidates: List<TransitJourney>,
    ): TransitJourney? =
        candidates
            .asSequence()
            .filter { it.provider.equals(selected.provider, ignoreCase = true) }
            .filter { candidate -> matches(selected.rides, candidate.rideLegs) }
            .minWithOrNull(
                compareBy<TransitJourney> { it.arrivalAt }
                    .thenBy { it.departureAt }
            )

    internal fun matches(
        selectedRides: List<TransitRideSignature>,
        candidateRides: List<TransitJourneyLeg>,
    ): Boolean {
        if (selectedRides.size != candidateRides.size || selectedRides.isEmpty()) return false
        return selectedRides.zip(candidateRides).all { (selected, candidate) ->
            selected.mode == candidate.mode &&
                sameLine(selected, candidate) &&
                sameServiceClass(selected, candidate) &&
                sameStop(selected.fromIds, selected.fromName, candidate.from) &&
                sameStop(selected.toIds, selected.toName, candidate.to) &&
                sameDirection(selected, candidate)
        }
    }

    private fun sameLine(selected: TransitRideSignature, candidate: TransitJourneyLeg): Boolean {
        val selectedId = selected.providerRouteId?.normalizeIdentity()
        val candidateId = candidate.line?.providerRouteId?.normalizeIdentity()
        if (selectedId != null && candidateId != null) return selectedId == candidateId

        val selectedName = selected.lineName.normalizeRouteName()
        val candidateName = candidate.line?.name.normalizeRouteName()
        return selectedName != null && candidateName != null && selectedName == candidateName
    }

    /**
     * 같은 지하철 노선 ID에서도 일반/급행은 정차역과 도착시각이 달라질 수 있다.
     * 어느 한쪽이라도 종별 증거가 없는 legacy/UNKNOWN 경로는 오매칭보다 미매칭을 택한다.
     * 버스는 이 속성의 적용 대상이 아니므로 기존 경로와 동일하게 매칭한다.
     */
    private fun sameServiceClass(
        selected: TransitRideSignature,
        candidate: TransitJourneyLeg,
    ): Boolean {
        if (selected.mode != TransitLegMode.SUBWAY) return true
        val selectedClass = selected.serviceClass
        val candidateClass = candidate.line?.serviceClass ?: TransitServiceClass.UNKNOWN
        return selectedClass != TransitServiceClass.UNKNOWN &&
            candidateClass != TransitServiceClass.UNKNOWN &&
            selectedClass == candidateClass
    }

    private fun sameStop(
        selectedIds: Set<String>,
        selectedName: String?,
        candidate: com.noLate.eta.domain.TransitStop?,
    ): Boolean {
        val candidateIds = candidate?.stableIds().orEmpty()
        if (selectedIds.isNotEmpty() && candidateIds.isNotEmpty()) {
            return selectedIds
                .map(String::normalizeIdentity)
                .intersect(candidateIds.map(String::normalizeIdentity).toSet())
                .isNotEmpty()
        }
        val expectedName = selectedName.normalizeStopName()
        val actualName = candidate?.name.normalizeStopName()
        return expectedName != null && actualName != null && expectedName == actualName
    }

    private fun sameDirection(
        selected: TransitRideSignature,
        candidate: TransitJourneyLeg,
    ): Boolean {
        val selectedCode = selected.directionCode?.trim()?.uppercase()?.takeIf(String::isNotBlank)
        val candidateCode = candidate.directionCode?.trim()?.uppercase()?.takeIf(String::isNotBlank)
        if (selectedCode != null && candidateCode != null) return selectedCode == candidateCode

        val selectedName = selected.directionName.normalizeDirectionName()
        val candidateName = candidate.directionName.normalizeDirectionName()
        if (selectedName != null && candidateName != null) {
            return selectedName.contains(candidateName) || candidateName.contains(selectedName)
        }

        // 저장 경로에 방향이 있는데 공급자 후보에는 비교 가능한 방향 메타데이터가 없으면
        // 반대 방향을 같은 경로로 오인하지 않도록 fail closed한다. 저장 데이터 자체가 방향을
        // 갖지 않는 legacy 경로만 이전 호환성을 위해 방향 검증을 생략한다.
        return selectedCode == null && selectedName == null
    }
}

private fun String?.normalizeRouteName(): String? =
    this
        ?.replace(WHITESPACE_PATTERN, "")
        ?.replace("수도권", "")
        ?.replace("지하철", "")
        ?.replace("버스", "")
        ?.replace("노선", "")
        ?.removeSuffix("번")
        ?.lowercase()
        ?.takeIf(String::isNotBlank)

private fun String?.normalizeStopName(): String? =
    this
        ?.replace(PARENTHESIZED_PATTERN, "")
        ?.replace(WHITESPACE_PATTERN, "")
        ?.removeSuffix("역")
        ?.lowercase()
        ?.takeIf(String::isNotBlank)

private fun String?.normalizeDirectionName(): String? =
    this
        ?.replace(WHITESPACE_PATTERN, "")
        ?.removeSuffix("방면")
        ?.removeSuffix("행")
        ?.removeSuffix("역")
        ?.lowercase()
        ?.takeIf(String::isNotBlank)

private fun String.normalizeIdentity(): String = trim().lowercase()

private val WHITESPACE_PATTERN = Regex("""\s+""")
private val PARENTHESIZED_PATTERN = Regex("""\([^)]*\)""")
