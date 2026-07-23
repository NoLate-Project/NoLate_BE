CREATE TABLE IF NOT EXISTS schedule_calendars (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Shared calendar primary key',
    owner_member_id BIGINT NOT NULL COMMENT 'Current calendar owner member id',
    legacy_category_id BIGINT NULL COMMENT 'Legacy shared category id used during migration',
    title VARCHAR(80) NOT NULL COMMENT 'Shared calendar title',
    color VARCHAR(32) NOT NULL DEFAULT '#2F80FF' COMMENT 'Shared calendar display color',
    default_content_mode VARCHAR(30) NOT NULL DEFAULT 'SCHEDULE_ONLY' COMMENT 'Default shared content mode',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'Calendar lifecycle status',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_calendars_legacy_category (legacy_category_id),
    INDEX idx_schedule_calendars_owner_status (owner_member_id, status, deleted)
) COMMENT='Shared schedule calendars';

CREATE TABLE IF NOT EXISTS schedule_calendar_members (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Shared calendar membership primary key',
    calendar_id BIGINT NOT NULL COMMENT 'Shared calendar id',
    member_id BIGINT NOT NULL COMMENT 'Member id',
    role VARCHAR(20) NOT NULL COMMENT 'OWNER, EDITOR, or VIEWER',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'Membership lifecycle status',
    route_reminder_enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'Missing route reminder preference',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_calendar_members_calendar_member (calendar_id, member_id),
    INDEX idx_schedule_calendar_members_member_status (member_id, status, deleted),
    INDEX idx_schedule_calendar_members_calendar_status (calendar_id, status, deleted),
    CONSTRAINT fk_schedule_calendar_members_calendar
        FOREIGN KEY (calendar_id) REFERENCES schedule_calendars (id)
        ON DELETE CASCADE
) COMMENT='Shared calendar memberships';

CREATE TABLE IF NOT EXISTS schedules (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Schedule primary key',
    member_id BIGINT NOT NULL COMMENT 'Owner member id',
    category_id BIGINT NULL COMMENT 'Current schedule category id for share permission lookup',
    calendar_id BIGINT NULL COMMENT 'Shared calendar id',
    schedule_type VARCHAR(20) NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL or ROUTE schedule',
    calendar_content_mode_override VARCHAR(30) NULL COMMENT 'Per-schedule shared content override',
    external_source_key VARCHAR(64) NULL COMMENT 'Member-scoped external calendar occurrence idempotency key',
    title VARCHAR(120) NOT NULL COMMENT 'Schedule title',
    start_at DATETIME(6) NOT NULL COMMENT 'Schedule start time',
    end_at DATETIME(6) NOT NULL COMMENT 'Schedule end time',
    has_end_time BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'End time input flag',
    all_day BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'All-day schedule flag',
    notes TEXT NULL COMMENT 'Schedule memo',
    route_setup_required BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Whether route setup is still required after quick share',
    created_at DATETIME(6) NULL COMMENT 'BaseAt created time',
    updated_at DATETIME(6) NULL COMMENT 'BaseAt updated time',
    deleted_at DATETIME(6) NULL COMMENT 'Soft delete time',
    deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Soft delete flag',
    create_dt DATETIME(6) NULL COMMENT 'BaseEntity created time',
    update_dt DATETIME(6) NULL COMMENT 'BaseEntity updated time',
    PRIMARY KEY (id),
    INDEX idx_schedules_member_deleted_start (member_id, deleted, start_at),
    INDEX idx_schedules_category_deleted_start (category_id, deleted, start_at),
    INDEX idx_schedules_calendar_deleted_start (calendar_id, deleted, start_at),
    UNIQUE KEY uk_schedules_member_external_source (member_id, external_source_key),
    CONSTRAINT fk_schedules_calendar
        FOREIGN KEY (calendar_id) REFERENCES schedule_calendars (id)
        ON DELETE SET NULL
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

CREATE TABLE IF NOT EXISTS schedule_categories (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Schedule category primary key',
    member_id BIGINT NOT NULL COMMENT 'Owner member id',
    title VARCHAR(80) NOT NULL COMMENT 'User-defined category title',
    color VARCHAR(32) NOT NULL DEFAULT '#5A96FF' COMMENT 'Category display color',
    icon_key VARCHAR(40) NULL COMMENT 'UI icon key',
    sort_order INT NOT NULL DEFAULT 0 COMMENT 'User-defined sort order',
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_schedule_categories_member_deleted_sort (member_id, deleted, sort_order)
) COMMENT='User-defined schedule categories';

CREATE TABLE IF NOT EXISTS schedule_shares (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Schedule share primary key',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    schedule_id BIGINT NOT NULL COMMENT 'Shared schedule id',
    owner_member_id BIGINT NOT NULL COMMENT 'Schedule owner member id',
    target_member_id BIGINT NOT NULL COMMENT 'Shared target member id',
    permission VARCHAR(30) NOT NULL COMMENT 'Share permission',
    content_mode VARCHAR(30) NOT NULL DEFAULT 'SCHEDULE_AND_TRAVEL' COMMENT 'Shared content mode',
    status VARCHAR(30) NOT NULL COMMENT 'Share status',
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_shares_schedule_target (schedule_id, target_member_id),
    INDEX idx_schedule_shares_target_status (target_member_id, status, deleted),
    INDEX idx_schedule_shares_owner_schedule (owner_member_id, schedule_id),
    CONSTRAINT fk_schedule_shares_schedule
        FOREIGN KEY (schedule_id) REFERENCES schedules (id)
        ON DELETE CASCADE
) COMMENT='Per-schedule share permissions';

CREATE TABLE IF NOT EXISTS schedule_category_shares (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Schedule category share primary key',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    category_id BIGINT NOT NULL COMMENT 'Shared schedule category id',
    owner_member_id BIGINT NOT NULL COMMENT 'Schedule category owner member id',
    target_member_id BIGINT NOT NULL COMMENT 'Shared target member id',
    permission VARCHAR(30) NOT NULL COMMENT 'Share permission',
    status VARCHAR(30) NOT NULL COMMENT 'Share status',
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_category_shares_category_target (category_id, target_member_id),
    INDEX idx_schedule_category_shares_target_status (target_member_id, status, deleted),
    INDEX idx_schedule_category_shares_owner_category (owner_member_id, category_id),
    CONSTRAINT fk_schedule_category_shares_category
        FOREIGN KEY (category_id) REFERENCES schedule_categories (id)
        ON DELETE CASCADE
) COMMENT='Per-schedule-category share permissions';

CREATE TABLE IF NOT EXISTS schedule_share_invitations (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Share invitation primary key',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    resource_type VARCHAR(30) NOT NULL COMMENT 'SCHEDULE or CATEGORY',
    resource_id BIGINT NOT NULL COMMENT 'Schedule id or category id',
    owner_member_id BIGINT NOT NULL COMMENT 'Resource owner member id',
    permission VARCHAR(30) NOT NULL COMMENT 'Permission granted on accept',
    content_mode VARCHAR(30) NOT NULL DEFAULT 'SCHEDULE_AND_TRAVEL' COMMENT 'Content mode granted on accept',
    token_hash VARCHAR(128) NOT NULL COMMENT 'SHA-256 hash of invitation token',
    status VARCHAR(30) NOT NULL COMMENT 'Invitation status',
    expires_at DATETIME(6) NOT NULL COMMENT 'Invitation expiration datetime',
    max_accept_count INT NOT NULL DEFAULT 1 COMMENT 'Maximum accepted members',
    accepted_count INT NOT NULL DEFAULT 0 COMMENT 'Accepted members count',
    accepted_member_id BIGINT NULL COMMENT 'Last accepted member id for single-use links',
    accepted_at DATETIME(6) NULL COMMENT 'Last accepted datetime',
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_share_invitations_token_hash (token_hash),
    INDEX idx_schedule_share_invitations_owner_resource (owner_member_id, resource_type, resource_id),
    INDEX idx_schedule_share_invitations_status_expires (status, expires_at)
) COMMENT='Link-based schedule and category share invitations';

CREATE TABLE IF NOT EXISTS schedule_routes (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Schedule route primary key',
    schedule_id BIGINT NOT NULL COMMENT 'Schedule id',
    travel_minutes INT NULL COMMENT 'Estimated travel minutes',
    depart_at DATETIME(6) NULL COMMENT 'Departure time',
    departed_at DATETIME(6) NULL COMMENT 'Departure completion time',
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

CREATE TABLE IF NOT EXISTS schedule_travel_plans (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Personal travel plan primary key',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    schedule_id BIGINT NOT NULL COMMENT 'Shared schedule id',
    member_id BIGINT NOT NULL COMMENT 'Travel plan owner member id',
    travel_minutes INT NULL COMMENT 'Estimated travel minutes',
    depart_at DATETIME(6) NULL COMMENT 'Member-specific departure time',
    travel_mode VARCHAR(20) NULL COMMENT 'Member-specific travel mode',
    origin_name VARCHAR(255) NULL COMMENT 'Personal origin name',
    origin_address VARCHAR(500) NULL COMMENT 'Personal origin address',
    origin_lat DOUBLE NULL COMMENT 'Personal origin latitude',
    origin_lng DOUBLE NULL COMMENT 'Personal origin longitude',
    route_json LONGTEXT NULL COMMENT 'Member-selected route detail JSON',
    notification_enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Member-specific departure notification flag',
    notification_lead_minutes INT NULL COMMENT 'Member-specific monitoring lead minutes',
    notification_interval_minutes INT NULL COMMENT 'Member-specific ETA refresh interval',
    schedule_fingerprint VARCHAR(64) NOT NULL COMMENT 'Schedule time and destination fingerprint at save time',
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_travel_plans_schedule_member (schedule_id, member_id),
    INDEX idx_schedule_travel_plans_schedule (schedule_id),
    INDEX idx_schedule_travel_plans_member (member_id),
    INDEX idx_schedule_travel_plans_member_depart_at (member_id, depart_at),
    CONSTRAINT fk_schedule_travel_plans_schedule
        FOREIGN KEY (schedule_id) REFERENCES schedules (id)
        ON DELETE CASCADE
) COMMENT='Per-member travel plans for shared schedules';

CREATE TABLE IF NOT EXISTS schedule_route_setup_reminders (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'D-3 route setup reminder primary key',
    schedule_id BIGINT NOT NULL COMMENT 'Route schedule id',
    member_id BIGINT NOT NULL COMMENT 'Reminder recipient member id',
    schedule_fingerprint VARCHAR(64) NOT NULL COMMENT 'Schedule condition fingerprint',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, SENT, CANCELLED, or FAILED',
    attempts INT NOT NULL DEFAULT 0 COMMENT 'Physical push attempt count',
    next_attempt_at DATETIME(6) NOT NULL COMMENT 'Next dispatch time',
    sent_at DATETIME(6) NULL COMMENT 'Logical dispatch completion time',
    last_error VARCHAR(500) NULL COMMENT 'Last dispatch error',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_route_setup_reminders_schedule_member_fingerprint
        (schedule_id, member_id, schedule_fingerprint),
    INDEX idx_route_setup_reminders_dispatch (status, next_attempt_at, id),
    INDEX idx_route_setup_reminders_member (member_id, id),
    CONSTRAINT fk_route_setup_reminders_schedule
        FOREIGN KEY (schedule_id) REFERENCES schedules (id)
        ON DELETE CASCADE
) COMMENT='D-3 per-member route setup reminder outbox';

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
    last_reminder_boundary_at DATETIME(6) NULL COMMENT 'Last 5-minute reminder boundary time',
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
    UNIQUE KEY uk_schedule_push_job_schedule_member (schedule_id, member_id),
    INDEX idx_schedule_push_job_status_next_check_at (status, next_check_at),
    INDEX idx_schedule_push_job_member_id (member_id),
    INDEX idx_schedule_push_job_schedule_id (schedule_id)
) COMMENT='Schedule push jobs';

CREATE TABLE IF NOT EXISTS app_notifications (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'In-app notification primary key',
    member_id BIGINT NOT NULL COMMENT 'Notification recipient member id',
    deduplication_key VARCHAR(180) NULL COMMENT 'Logical event key used to merge concurrent delivery attempts',
    type VARCHAR(80) NOT NULL COMMENT 'Client navigation and presentation type',
    schedule_id BIGINT NULL COMMENT 'Related schedule id when applicable',
    category_id BIGINT NULL COMMENT 'Related category id when applicable',
    title VARCHAR(200) NOT NULL COMMENT 'Notification title',
    body VARCHAR(1000) NOT NULL COMMENT 'Notification body',
    data_json LONGTEXT NOT NULL COMMENT 'Original navigation payload as JSON',
    created_at DATETIME(6) NOT NULL COMMENT 'Logical notification creation time',
    read_at DATETIME(6) NULL COMMENT 'First read time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_notifications_member_deduplication (member_id, deduplication_key),
    INDEX idx_app_notifications_member_id_id (member_id, id),
    INDEX idx_app_notifications_member_read_at (member_id, read_at)
) COMMENT='Durable user-facing in-app notification inbox';

-- Existing environments may have created data_json with a smaller text type while the
-- entity mapping was being introduced. Keep the executable bootstrap schema corrective.
ALTER TABLE app_notifications
    MODIFY COLUMN data_json LONGTEXT NOT NULL COMMENT 'Original navigation payload as JSON';

CREATE TABLE IF NOT EXISTS favorite_place_categories (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Favorite place category primary key',
    member_id BIGINT NOT NULL COMMENT 'Owner member id',
    name VARCHAR(80) NOT NULL COMMENT 'User-defined category name',
    color VARCHAR(32) NOT NULL DEFAULT '#5A96FF' COMMENT 'Category display color',
    icon_key VARCHAR(40) NULL COMMENT 'Category icon key',
    sort_order INT NOT NULL DEFAULT 0 COMMENT 'User-defined sort order',
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_favorite_place_categories_member_deleted_sort (member_id, deleted, sort_order)
) COMMENT='User-defined favorite place categories';

CREATE TABLE IF NOT EXISTS favorite_places (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Favorite place primary key',
    member_id BIGINT NOT NULL COMMENT 'Owner member id',
    category_id BIGINT NULL COMMENT 'Favorite place category id',
    label VARCHAR(120) NOT NULL COMMENT 'User-defined place label',
    place_name VARCHAR(255) NULL COMMENT 'Provider place name',
    address VARCHAR(500) NULL COMMENT 'Place address',
    lat DOUBLE NOT NULL COMMENT 'Latitude',
    lng DOUBLE NOT NULL COMMENT 'Longitude',
    provider VARCHAR(30) NULL COMMENT 'Place provider',
    provider_place_id VARCHAR(128) NULL COMMENT 'Provider place id',
    is_default_origin BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Default origin flag',
    sort_order INT NOT NULL DEFAULT 0 COMMENT 'User-defined sort order',
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_favorite_places_member_deleted_sort (member_id, deleted, sort_order),
    INDEX idx_favorite_places_member_default_origin (member_id, deleted, is_default_origin),
    INDEX idx_favorite_places_category (category_id),
    CONSTRAINT fk_favorite_places_category
        FOREIGN KEY (category_id) REFERENCES favorite_place_categories (id)
        ON DELETE SET NULL
) COMMENT='User favorite places';

CREATE TABLE IF NOT EXISTS recent_route_places (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Recent route place primary key',
    member_id BIGINT NOT NULL COMMENT 'Owner member id',
    label VARCHAR(120) NOT NULL COMMENT 'Display place label',
    place_name VARCHAR(255) NULL COMMENT 'Provider place name',
    address VARCHAR(500) NULL COMMENT 'Place address',
    lat DOUBLE NOT NULL COMMENT 'Latitude',
    lng DOUBLE NOT NULL COMMENT 'Longitude',
    provider VARCHAR(30) NULL COMMENT 'Place provider',
    provider_place_id VARCHAR(128) NULL COMMENT 'Provider place id',
    last_used_at DATETIME(6) NOT NULL COMMENT 'Last selected datetime',
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_recent_route_places_member_deleted_used (member_id, deleted, last_used_at),
    INDEX idx_recent_route_places_member_provider (member_id, deleted, provider, provider_place_id),
    INDEX idx_recent_route_places_member_coords (member_id, deleted, lat, lng)
) COMMENT='User recent route search places';

CREATE TABLE IF NOT EXISTS member_consents (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Consent audit primary key',
    member_id BIGINT NOT NULL COMMENT 'Member who accepted the document',
    consent_type VARCHAR(40) NOT NULL COMMENT 'Accepted consent document type',
    document_version VARCHAR(40) NOT NULL COMMENT 'Accepted document version',
    agreed_at DATETIME(6) NOT NULL COMMENT 'Acceptance datetime',
    withdrawn_at DATETIME(6) NULL COMMENT 'Withdrawal datetime when applicable',
    source VARCHAR(30) NOT NULL COMMENT 'Signup channel that collected consent',
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_consents_member_type_version (member_id, consent_type, document_version),
    INDEX idx_member_consents_member_agreed_at (member_id, agreed_at)
) COMMENT='Versioned member signup consent audit';

CREATE TABLE IF NOT EXISTS calendar_day_cache (
    solar_date DATE NOT NULL COMMENT 'Gregorian date in the Asia/Seoul calendar',
    lunar_year INT NULL COMMENT 'Corresponding lunar calendar year',
    lunar_month INT NULL COMMENT 'Corresponding lunar calendar month',
    lunar_day INT NULL COMMENT 'Corresponding lunar calendar day',
    leap_month BOOLEAN NULL COMMENT 'Whether the lunar date belongs to a leap month',
    lunar_synced_at DATETIME(6) NULL COMMENT 'Last successful KASI lunar synchronization time in KST',
    holidays_synced_at DATETIME(6) NULL COMMENT 'Last successful KASI holiday synchronization time in KST',
    updated_at DATETIME(6) NOT NULL COMMENT 'Last cache update time in KST',
    PRIMARY KEY (solar_date),
    INDEX idx_calendar_day_cache_lunar_synced (lunar_synced_at),
    INDEX idx_calendar_day_cache_holidays_synced (holidays_synced_at)
) COMMENT='KASI Gregorian-to-lunar and holiday synchronization cache';

CREATE TABLE IF NOT EXISTS public_holidays (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Public holiday cache primary key',
    holiday_date DATE NOT NULL COMMENT 'Holiday date in the Asia/Seoul calendar',
    name VARCHAR(100) NOT NULL COMMENT 'Korean holiday display name',
    holiday_type VARCHAR(30) NOT NULL COMMENT 'Calendar metadata holiday type',
    source VARCHAR(30) NOT NULL COMMENT 'Calendar data provider',
    updated_at DATETIME(6) NOT NULL COMMENT 'Last successful cache update time in KST',
    PRIMARY KEY (id),
    UNIQUE KEY uk_public_holidays_date_name_type (holiday_date, name, holiday_type),
    INDEX idx_public_holidays_date (holiday_date)
) COMMENT='Shared Republic of Korea public holiday cache';
