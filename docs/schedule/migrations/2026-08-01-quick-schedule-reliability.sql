-- Content-free quick schedule confidence calibration telemetry.
-- Intentionally excludes input text, OCR/STT transcripts, titles, notes and places.
CREATE TABLE IF NOT EXISTS quick_schedule_parse_telemetry (
    analysis_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    member_id BIGINT NOT NULL,
    input_type VARCHAR(30) NOT NULL,
    client_platform VARCHAR(20) NOT NULL,
    parse_source VARCHAR(30) NOT NULL,
    confidence_level VARCHAR(20) NULL,
    overall_confidence DOUBLE NULL,
    recognition_confidence DOUBLE NULL,
    date_confidence DOUBLE NULL,
    time_confidence DOUBLE NULL,
    destination_confidence DOUBLE NULL,
    needs_review BOOLEAN NOT NULL,
    confidence_version VARCHAR(40) NOT NULL,
    outcome VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    date_verification VARCHAR(30) NOT NULL DEFAULT 'UNTOUCHED',
    time_verification VARCHAR(30) NOT NULL DEFAULT 'UNTOUCHED',
    destination_verification VARCHAR(30) NOT NULL DEFAULT 'UNTOUCHED',
    global_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    feedback_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (analysis_id),
    INDEX idx_quick_parse_member (member_id, created_at),
    INDEX idx_quick_parse_calibration (input_type, confidence_level, outcome, created_at),
    INDEX idx_quick_parse_expiry (expires_at),
    CONSTRAINT chk_quick_parse_outcome CHECK (outcome IN ('PENDING', 'SAVED', 'CANCELLED')),
    CONSTRAINT chk_quick_parse_score_range CHECK (
        (overall_confidence IS NULL OR overall_confidence BETWEEN 0 AND 1) AND
        (recognition_confidence IS NULL OR recognition_confidence BETWEEN 0 AND 1) AND
        (date_confidence IS NULL OR date_confidence BETWEEN 0 AND 1) AND
        (time_confidence IS NULL OR time_confidence BETWEEN 0 AND 1) AND
        (destination_confidence IS NULL OR destination_confidence BETWEEN 0 AND 1)
    )
);
