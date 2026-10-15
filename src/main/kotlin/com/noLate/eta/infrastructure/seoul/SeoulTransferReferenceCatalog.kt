package com.noLate.eta.infrastructure.seoul

import com.noLate.eta.domain.SeoulSubwayLine
import com.noLate.eta.domain.TransitTransferReference
import org.springframework.stereotype.Component
import com.noLate.eta.application.port.TransitTransferReferenceProvider

/** 공개 CSV의 배포 시점 스냅샷. 실시간 API나 사용자 이동 기록을 수집하지 않는다. */
@Component
class SeoulTransferReferenceCatalog : TransitTransferReferenceProvider {
    private val rows: List<TransitTransferReference> = requireNotNull(javaClass.getResourceAsStream(
        "/eta/seoul-transfer-reference-20250331.csv")) .bufferedReader(Charsets.UTF_8).useLines { lines ->
        lines.drop(1).filter(String::isNotBlank).map { line ->
            val columns = line.split(',')
            require(columns.size == 6)
            val time = columns[5].split(':').map(String::toLong)
            require(time.size == 2 && time[1] in 0..59)
            TransitTransferReference(columns[2], "${columns[1]}호선", columns[3],
                columns[4].toLong(), time[0] * 60 + time[1])
        }.toList()
    }

    override fun find(station: String, from: SeoulSubwayLine, to: SeoulSubwayLine): TransitTransferReference? =
        rows.singleOrNull { it.stationName == station && it.fromLine == from.displayName && it.toLine == to.displayName }
}
