package com.noLate.eta.application.port

import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneySearchRequest

interface TransitJourneyProvider {
    val providerId: String

    fun search(request: TransitJourneySearchRequest): List<TransitJourney>
}
