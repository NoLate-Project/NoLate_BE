CREATE TABLE IF NOT EXISTS schedules (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Schedule primary key',
    member_id BIGINT NOT NULL COMMENT 'Owner member id',
    title VARCHAR(120) NOT NULL COMMENT 'Schedule title',
    start_at DATETIME(6) NOT NULL COMMENT 'Schedule start time',
    end_at DATETIME(6) NOT NULL COMMENT 'Schedule end time',
    has_end_time BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'End time input flag',
    all_day BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'All-day schedule flag',
    notes TEXT NULL COMMENT 'Schedule memo',
    created_at DATETIME(6) NULL COMMENT 'BaseAt created time',
    updated_at DATETIME(6) NULL COMMENT 'BaseAt updated time',
    deleted_at DATETIME(6) NULL COMMENT 'Soft delete time',
    deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Soft delete flag',
    create_dt DATETIME(6) NULL COMMENT 'BaseEntity created time',
    update_dt DATETIME(6) NULL COMMENT 'BaseEntity updated time',
    PRIMARY KEY (id),
    INDEX idx_schedules_member_deleted_start (member_id, deleted, start_at)
) COMMENT='Schedule core table';

CREATE TABLE IF NOT EXISTS schedule_category_snapshots (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Schedule category snapshot primary key',
    schedule_id BIGINT NOT NULL COMMENT 'Schedule id',
    category_id VARCHAR(64) NOT NULL COMMENT 'Selected category id',
    title VARCHAR(80) NOT NULL COMMENT 'Category display title',
    color VARCHAR(32) NOT NULL COMMENT 'Category display color',
    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_category_snapshots_schedule (schedule_id),
    CONSTRAINT fk_schedule_category_snapshots_schedule
        FOREIGN KEY (schedule_id) REFERENCES schedules (id)
        ON DELETE CASCADE
) COMMENT='Category display snapshot for each schedule';

CREATE TABLE IF NOT EXISTS schedule_routes (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Schedule route primary key',
    schedule_id BIGINT NOT NULL COMMENT 'Schedule id',
    travel_minutes INT NULL COMMENT 'Estimated travel minutes',
    depart_at DATETIME(6) NULL COMMENT 'Departure time',
    travel_mode VARCHAR(20) NULL COMMENT 'Travel mode',
    location_name VARCHAR(255) NULL COMMENT 'Location or route summary',
    origin_name VARCHAR(255) NULL COMMENT 'Origin place name',
    origin_address VARCHAR(500) NULL COMMENT 'Origin address',
    origin_lat DOUBLE NULL COMMENT 'Origin latitude',
    origin_lng DOUBLE NULL COMMENT 'Origin longitude',
    destination_name VARCHAR(255) NULL COMMENT 'Destination place name',
    destination_address VARCHAR(500) NULL COMMENT 'Destination address',
    destination_lat DOUBLE NULL COMMENT 'Destination latitude',
    destination_lng DOUBLE NULL COMMENT 'Destination longitude',
    route_json LONGTEXT NULL COMMENT 'Selected route detail JSON',
    notification_enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Realtime departure notification flag',
    notification_lead_minutes INT NULL COMMENT 'Monitoring lead minutes',
    notification_interval_minutes INT NULL COMMENT 'Notification interval minutes',
    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_routes_schedule (schedule_id),
    INDEX idx_schedule_routes_depart_at (depart_at),
    CONSTRAINT fk_schedule_routes_schedule
        FOREIGN KEY (schedule_id) REFERENCES schedules (id)
        ON DELETE CASCADE
) COMMENT='Travel and route detail for each schedule';

CREATE TABLE IF NOT EXISTS schedule_push_job (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Push job primary key',
    version BIGINT NULL COMMENT 'Optimistic lock version',
    member_id BIGINT NOT NULL COMMENT 'Member id',
    schedule_id BIGINT NOT NULL COMMENT 'Schedule id',
    schedule_at DATETIME(6) NOT NULL COMMENT 'Schedule time',
    departure_at DATETIME(6) NOT NULL COMMENT 'Initial recommended departure time',
    monitor_start_at DATETIME(6) NOT NULL COMMENT 'Traffic monitoring start time',
    interval_minutes INT NOT NULL COMMENT 'Traffic check interval',
    status VARCHAR(30) NOT NULL COMMENT 'Job status',
    next_check_at DATETIME(6) NOT NULL COMMENT 'Next traffic check time',
    last_travel_minutes INT NULL COMMENT 'Last travel minutes',
    last_recommended_departure_at DATETIME(6) NULL COMMENT 'Last recommended departure time',
    last_notified_departure_at DATETIME(6) NULL COMMENT 'Last departure time notified to the user',
    last_checked_at DATETIME(6) NULL COMMENT 'Last checked time',
    last_pushed_at DATETIME(6) NULL COMMENT 'Last push sent time',
    check_count INT NOT NULL DEFAULT 0 COMMENT 'Traffic check count',
    retry_count INT NOT NULL DEFAULT 0 COMMENT 'Retry count',
    locked_by VARCHAR(100) NULL COMMENT 'Worker id',
    locked_at DATETIME(6) NULL COMMENT 'Locked time',
    failure_reason VARCHAR(500) NULL COMMENT 'Last failure reason',
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_push_job_schedule_id (schedule_id),
    INDEX idx_schedule_push_job_status_next_check_at (status, next_check_at),
    INDEX idx_schedule_push_job_member_id (member_id),
    INDEX idx_schedule_push_job_schedule_id (schedule_id)
) COMMENT='Schedule push jobs';
