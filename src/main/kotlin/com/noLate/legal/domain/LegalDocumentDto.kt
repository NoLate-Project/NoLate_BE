package com.noLate.legal.domain

data class LegalDocumentDto(
    val type: LegalDocumentType,
    val title: String,
    val version: String,
    val effectiveDate: String,
    val summary: String,
    val sections: List<LegalDocumentSectionDto>,
)

data class LegalDocumentSectionDto(
    val title: String,
    val body: List<String>,
)

enum class LegalDocumentType {
    PRIVACY_POLICY,
}
