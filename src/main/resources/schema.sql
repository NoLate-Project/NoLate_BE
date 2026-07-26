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
    last_handled_departure_at DATETIME(6) NULL COMMENT 'Last confirmed or uncertain logical departure time',
    last_handled_reminder_boundary_at DATETIME(6) NULL COMMENT 'Last confirmed or uncertain logical reminder boundary',
    last_checked_at DATETIME(6) NULL COMMENT 'Last checked time',
    last_live_fetched_at DATETIME(6) NULL COMMENT 'Last successful live provider fetch time',
    last_live_travel_minutes INT NULL COMMENT 'Last trusted live provider travel minutes',
    last_eta_source VARCHAR(30) NULL COMMENT 'LIVE_PROVIDER, SELECTED_ROUTE, or SAVED_FALLBACK',
    last_eta_stale BOOLEAN NULL COMMENT 'Whether the last ETA used a stale snapshot',
    last_eta_failure_reason VARCHAR(500) NULL COMMENT 'Stable fallback or provider failure code and safe message',
    last_eta_route_fingerprint VARCHAR(64) NULL COMMENT 'Route fingerprint used by the latest ETA snapshot',
    last_traffic_change_minutes INT NULL COMMENT 'Last comparable live-to-live ETA delta in minutes',
    last_changed_at DATETIME(6) NULL COMMENT 'Time of the last comparable live-to-live ETA change',
    last_pushed_at DATETIME(6) NULL COMMENT 'Last push sent time',
    departure_notice_sent_at DATETIME(6) NULL COMMENT 'First depart-now notification sent time',
    handled_departure_notice_at DATETIME(6) NULL COMMENT 'First confirmed or uncertain DEPART_NOW handling time',
    last_departure_reminder_stage VARCHAR(40) NULL COMMENT 'Last handled departure follow-up stage',
    last_departure_reminder_boundary_at DATETIME(6) NULL COMMENT 'Last handled departure follow-up boundary',
    last_handled_departure_reminder_stage VARCHAR(40) NULL COMMENT 'Last confirmed or uncertain follow-up stage',
    last_handled_departure_reminder_boundary_at DATETIME(6) NULL COMMENT 'Last confirmed or uncertain follow-up boundary',
    last_uncertain_at DATETIME(6) NULL COMMENT 'Most recent ambiguous delivery handling time',
    snoozed_until DATETIME(6) NULL COMMENT 'User requested reminder time',
    check_count INT NOT NULL DEFAULT 0 COMMENT 'Traffic check count',
    retry_count INT NOT NULL DEFAULT 0 COMMENT 'Retry count',
    notification_generation BIGINT NOT NULL DEFAULT 0 COMMENT 'Notification event generation incremented on schedule changes',
    notification_input_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Deterministic notification semantic input SHA-256',
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

CREATE TABLE IF NOT EXISTS schedule_notification_action_receipts (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Action receipt primary key',
    key_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'Case-sensitive SHA-256 of the unpersisted Idempotency-Key',
    member_id BIGINT NOT NULL COMMENT 'Authenticated action member id',
    schedule_id BIGINT NOT NULL COMMENT 'Bound schedule id',
    action_type VARCHAR(24) NOT NULL COMMENT 'DEPART_NOW or SNOOZE',
    result_departed_at DATETIME(6) NULL COMMENT 'Authoritative first departure time',
    result_snoozed_until DATETIME(6) NULL COMMENT 'Snooze time returned by the one mutation',
    completed_at DATETIME(6) NULL COMMENT 'Completion time committed atomically with mutation',
    created_at DATETIME(6) NOT NULL COMMENT 'Receipt creation time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_notification_action_key_fingerprint (key_fingerprint),
    INDEX idx_schedule_notification_action_scope (member_id, schedule_id, action_type)
) COMMENT='Durable idempotency receipts for schedule notification actions';

CREATE TABLE IF NOT EXISTS push_device_token (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    device_id VARCHAR(100) NULL,
    platform VARCHAR(20) NOT NULL,
    token VARCHAR(500) NOT NULL,
    token_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    device_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ownership_version BIGINT NOT NULL DEFAULT 0,
    dispatch_lease_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    dispatch_lease_until DATETIME(6) NULL,
    retirement_requested BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_push_device_token_token_fingerprint (token_fingerprint),
    UNIQUE KEY uk_push_device_token_device_fingerprint (device_fingerprint),
    INDEX idx_push_device_token_dispatch_lease (dispatch_lease_until, id)
) COMMENT='Current global byte-exact installation ownership; raw opaque values are never indexed';

CREATE TABLE IF NOT EXISTS push_send_history (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Provider attempt history primary key',
    member_id BIGINT NOT NULL COMMENT 'Notification recipient member id',
    device_token_id BIGINT NULL COMMENT 'Token row id at send time',
    device_id VARCHAR(100) NULL COMMENT 'Legacy operational device label; never indexed',
    platform VARCHAR(20) NOT NULL COMMENT 'Push platform',
    schedule_id BIGINT NULL COMMENT 'Immutable schedule resource id when applicable',
    logical_event_key VARCHAR(100) NULL COMMENT 'Canonical durable outbox/source event key',
    category_id BIGINT NULL COMMENT 'Immutable category resource id when applicable',
    calendar_id BIGINT NULL COMMENT 'Immutable shared-calendar resource id when applicable',
    payload_type VARCHAR(80) NULL COMMENT 'Canonical push payload type',
    title VARCHAR(200) NOT NULL COMMENT 'Provider payload title',
    body VARCHAR(1000) NOT NULL COMMENT 'Provider payload body',
    data_json LONGTEXT NOT NULL COMMENT 'Canonical provider data payload',
    status VARCHAR(30) NOT NULL COMMENT 'SUCCESS, FAILED, UNKNOWN, INVALID_TOKEN, or NO_TOKEN',
    fcm_message_id VARCHAR(300) NULL COMMENT 'Provider acceptance message id',
    error_code VARCHAR(120) NULL COMMENT 'Sanitized provider/local error class or code',
    error_message VARCHAR(1000) NULL COMMENT 'Sanitized failure detail',
    sent_at DATETIME(6) NOT NULL COMMENT 'Provider result/history time',
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_push_send_history_member_event (member_id, logical_event_key),
    INDEX idx_push_send_history_category_member (category_id, member_id),
    INDEX idx_push_send_history_calendar_member (calendar_id, member_id)
) COMMENT='Per-attempt push provider history with durable source identity';

CREATE TABLE IF NOT EXISTS app_notifications (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'In-app notification primary key',
    member_id BIGINT NOT NULL COMMENT 'Notification recipient member id',
    deduplication_key VARCHAR(180) NULL COMMENT 'Logical event key used to merge concurrent delivery attempts',
    logical_event_key VARCHAR(100) NOT NULL COMMENT 'Durable logical push/outbox event key',
    type VARCHAR(80) NOT NULL COMMENT 'Client navigation and presentation type',
    schedule_id BIGINT NULL COMMENT 'Related schedule id when applicable',
    category_id BIGINT NULL COMMENT 'Related category id when applicable',
    calendar_id BIGINT NULL COMMENT 'Immutable shared-calendar authorization resource id',
    title VARCHAR(200) NOT NULL COMMENT 'Notification title',
    body VARCHAR(1000) NOT NULL COMMENT 'Notification body',
    data_json LONGTEXT NOT NULL COMMENT 'Original navigation payload as JSON',
    created_at DATETIME(6) NOT NULL COMMENT 'Logical notification creation time',
    read_at DATETIME(6) NULL COMMENT 'First read time',
    manifest_state VARCHAR(24) NOT NULL DEFAULT 'INBOX_ONLY'
        COMMENT 'INBOX_ONLY, OPEN, or immutable FROZEN recipient snapshot',
    manifest_recipient_count INT NOT NULL DEFAULT 0
        COMMENT 'Frozen delivery row count, including zero-device events',
    manifest_frozen_at DATETIME(6) NULL COMMENT 'Recipient snapshot linearization time',
    dispatch_status VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUIRED'
        COMMENT 'NOT_REQUIRED, PENDING, PROCESSING, COMPLETED, or FAILED',
    dispatch_attempt_count INT NOT NULL DEFAULT 0 COMMENT 'Durable outbox drainer claims',
    dispatch_failure_count INT NOT NULL DEFAULT 0
        COMMENT 'Actual retry-budget failures; expected deferrals do not increment',
    next_dispatch_at DATETIME(6) NULL COMMENT 'Next bounded drainer eligibility time',
    dispatch_locked_by VARCHAR(100) NULL COMMENT 'Outbox drainer lease owner',
    dispatch_locked_at DATETIME(6) NULL COMMENT 'Outbox drainer lease time',
    dispatch_completed_at DATETIME(6) NULL COMMENT 'Terminal drainer time',
    dispatch_failure_reason VARCHAR(500) NULL COMMENT 'Sanitized last drainer outcome',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_notifications_member_deduplication (member_id, deduplication_key),
    UNIQUE KEY uk_app_notifications_member_logical_event (member_id, logical_event_key),
    INDEX idx_app_notifications_member_id_id (member_id, id),
    INDEX idx_app_notifications_member_read_at (member_id, read_at),
    INDEX idx_app_notifications_calendar_id (calendar_id),
    INDEX idx_app_notifications_dispatch_due (dispatch_status, next_dispatch_at, id),
    INDEX idx_app_notifications_dispatch_lease (dispatch_status, dispatch_locked_at, id)
) COMMENT='Durable user-facing in-app notification inbox';

-- Existing environments may have created data_json with a smaller text type while the
-- entity mapping was being introduced. Keep the executable bootstrap schema corrective.
ALTER TABLE app_notifications
    MODIFY COLUMN data_json LONGTEXT NOT NULL COMMENT 'Original navigation payload as JSON';

CREATE TABLE IF NOT EXISTS push_deliveries (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Per-device logical push delivery primary key',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    member_id BIGINT NOT NULL COMMENT 'Notification recipient member id',
    event_key VARCHAR(100) NOT NULL COMMENT 'Durable logical event identifier',
    device_key VARCHAR(100) NOT NULL COMMENT 'Stable device id or one-way token fingerprint',
    device_token_id BIGINT NULL COMMENT 'Token row id at dispatch time; no foreign key so invalid-token removal keeps evidence',
    token_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Case-sensitive token ownership snapshot',
    token_ownership_version BIGINT NOT NULL COMMENT 'Token ownership version snapshot',
    device_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'One-way client device fingerprint',
    platform VARCHAR(20) NOT NULL COMMENT 'Push platform',
    schedule_id BIGINT NULL COMMENT 'Related schedule id when applicable',
    calendar_id BIGINT NULL COMMENT 'Frozen shared-calendar authorization resource id',
    payload_type VARCHAR(80) NULL COMMENT 'Push payload type',
    status VARCHAR(30) NOT NULL COMMENT 'PENDING, DISPATCHING, SUCCESS, FAILED, INVALID_TOKEN, or SUPERSEDED',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT 'Provider call attempt count',
    first_attempted_at DATETIME(6) NULL COMMENT 'First provider call boundary creation time',
    last_attempted_at DATETIME(6) NULL COMMENT 'Most recent provider call boundary time',
    delivered_at DATETIME(6) NULL COMMENT 'Provider success response time',
    provider_message_id VARCHAR(300) NULL COMMENT 'Provider message id after confirmed success',
    error_code VARCHAR(120) NULL COMMENT 'Provider or local transition failure class/code',
    error_message VARCHAR(1000) NULL COMMENT 'Sanitized failure detail without raw push token',
    PRIMARY KEY (id),
    UNIQUE KEY uk_push_deliveries_member_event_device (member_id, event_key, device_key),
    INDEX idx_push_deliveries_member_event (member_id, event_key),
    INDEX idx_push_deliveries_status_attempted_at (status, last_attempted_at),
    INDEX idx_push_deliveries_schedule_id (schedule_id),
    INDEX idx_push_deliveries_calendar_id (calendar_id)
) COMMENT='Durable at-most-once per-device push delivery boundary';

CREATE TABLE IF NOT EXISTS apple_authorization_code_receipts (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Immutable authorization-code receipt primary key',
    receipt_key VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'Random non-secret receipt identifier',
    authorization_code_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'Single-use authorization-code replay fingerprint',
    expected_subject_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'Expected Apple subject fingerprint at reservation time',
    client_id VARCHAR(255) NOT NULL COMMENT 'Expected Apple audience at reservation time',
    reserved_at DATETIME(6) NOT NULL COMMENT 'Committed reservation time before provider I/O',
    PRIMARY KEY (id),
    UNIQUE KEY uk_apple_authorization_receipts_receipt_key (receipt_key),
    UNIQUE KEY uk_apple_authorization_receipts_code_hash (authorization_code_hash)
) COMMENT='Immutable Apple authorization-code consume receipts';

CREATE TABLE IF NOT EXISTS apple_provider_credentials (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Encrypted Apple provider credential primary key',
    credential_key VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'Random envelope AAD identifier',
    source_receipt_key VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'First immutable authorization-code receipt that captured this token',
    member_id BIGINT NULL COMMENT 'Local account id retained only until provider revocation succeeds',
    apple_subject_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'One-way Apple subject fingerprint',
    refresh_token_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'One-way refresh-token deduplication fingerprint',
    client_id VARCHAR(255) NOT NULL COMMENT 'Apple client id that issued this token',
    encryption_key_id VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'Environment-owned envelope key id',
    initialization_vector VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'Base64 AES-GCM initialization vector',
    encrypted_refresh_token VARCHAR(16384) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'Base64 AES-256-GCM ciphertext; never plaintext',
    status VARCHAR(20) NOT NULL DEFAULT 'CAPTURED'
        COMMENT 'CAPTURED, ACTIVE, PENDING, PROCESSING, BLOCKED, MANUAL_ACTION, or REVOKED',
    capture_expires_at DATETIME(6) NULL COMMENT 'Deadline for binding or compensation',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT 'Physical Apple revoke attempts',
    next_attempt_at DATETIME(6) NULL COMMENT 'Next revocation eligibility time',
    locked_at DATETIME(6) NULL COMMENT 'Current revocation lease time',
    locked_by VARCHAR(80) NULL COMMENT 'Current revocation worker id',
    last_failure_code VARCHAR(120) NULL COMMENT 'Sanitized provider/local failure code',
    revoked_at DATETIME(6) NULL COMMENT 'Provider-confirmed token deletion time',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_apple_provider_credentials_credential_key (credential_key),
    UNIQUE KEY uk_apple_provider_credentials_refresh_token_hash (refresh_token_hash),
    INDEX idx_apple_provider_credentials_member_status (member_id, status, id),
    INDEX idx_apple_provider_credentials_due (status, next_attempt_at, id),
    INDEX idx_apple_provider_credentials_capture (status, capture_expires_at, id),
    INDEX idx_apple_provider_credentials_stale (status, locked_at, id),
    CONSTRAINT ck_apple_provider_credentials_status CHECK (
        status IN (
            'CAPTURED', 'ACTIVE', 'PENDING', 'PROCESSING',
            'BLOCKED', 'MANUAL_ACTION', 'REVOKED'
        )
        AND attempt_count >= 0
        AND (
            (
                status = 'CAPTURED'
                AND source_receipt_key IS NOT NULL
                AND member_id IS NULL
                AND apple_subject_hash IS NOT NULL
                AND refresh_token_hash IS NOT NULL
                AND encryption_key_id IS NOT NULL
                AND initialization_vector IS NOT NULL
                AND encrypted_refresh_token IS NOT NULL
                AND capture_expires_at IS NOT NULL
                AND next_attempt_at IS NULL
                AND locked_at IS NULL
                AND locked_by IS NULL
                AND revoked_at IS NULL
            )
            OR (
                status = 'ACTIVE'
                AND source_receipt_key IS NOT NULL
                AND member_id IS NOT NULL
                AND apple_subject_hash IS NOT NULL
                AND refresh_token_hash IS NOT NULL
                AND encryption_key_id IS NOT NULL
                AND initialization_vector IS NOT NULL
                AND encrypted_refresh_token IS NOT NULL
                AND capture_expires_at IS NULL
                AND next_attempt_at IS NULL
                AND locked_at IS NULL
                AND locked_by IS NULL
                AND revoked_at IS NULL
            )
            OR (
                status = 'PENDING'
                AND source_receipt_key IS NOT NULL
                AND apple_subject_hash IS NOT NULL
                AND refresh_token_hash IS NOT NULL
                AND encryption_key_id IS NOT NULL
                AND initialization_vector IS NOT NULL
                AND encrypted_refresh_token IS NOT NULL
                AND capture_expires_at IS NULL
                AND next_attempt_at IS NOT NULL
                AND locked_at IS NULL
                AND locked_by IS NULL
                AND revoked_at IS NULL
            )
            OR (
                status = 'PROCESSING'
                AND source_receipt_key IS NOT NULL
                AND apple_subject_hash IS NOT NULL
                AND refresh_token_hash IS NOT NULL
                AND encryption_key_id IS NOT NULL
                AND initialization_vector IS NOT NULL
                AND encrypted_refresh_token IS NOT NULL
                AND capture_expires_at IS NULL
                AND next_attempt_at IS NOT NULL
                AND locked_at IS NOT NULL
                AND locked_by IS NOT NULL
                AND revoked_at IS NULL
            )
            OR (
                status = 'BLOCKED'
                AND capture_expires_at IS NULL
                AND next_attempt_at IS NULL
                AND locked_at IS NULL
                AND locked_by IS NULL
                AND revoked_at IS NULL
            )
            OR (
                status = 'MANUAL_ACTION'
                AND source_receipt_key IS NULL
                AND member_id IS NULL
                AND apple_subject_hash IS NULL
                AND refresh_token_hash IS NULL
                AND encryption_key_id IS NULL
                AND initialization_vector IS NULL
                AND encrypted_refresh_token IS NULL
                AND capture_expires_at IS NULL
                AND next_attempt_at IS NULL
                AND locked_at IS NULL
                AND locked_by IS NULL
                AND last_failure_code IS NOT NULL
                AND revoked_at IS NULL
            )
            OR (
                status = 'REVOKED'
                AND source_receipt_key IS NULL
                AND member_id IS NULL
                AND apple_subject_hash IS NULL
                AND refresh_token_hash IS NULL
                AND encryption_key_id IS NULL
                AND initialization_vector IS NULL
                AND encrypted_refresh_token IS NULL
                AND capture_expires_at IS NULL
                AND next_attempt_at IS NULL
                AND locked_at IS NULL
                AND locked_by IS NULL
                AND revoked_at IS NOT NULL
            )
        )
    )
) COMMENT='Encrypted Sign in with Apple credentials and durable revoke leases';

-- Production never runs schema.sql, but keeping the marker table in the executable
-- development schema lets local schema inspection match the manual production DDL.
-- The production marker row itself is inserted only by the final verified migration.
CREATE TABLE IF NOT EXISTS application_schema_migrations (
    version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    description VARCHAR(255) NOT NULL,
    applied_at DATETIME(6) NOT NULL,
    PRIMARY KEY (version)
) COMMENT='Manually verified production schema versions';

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

CREATE TABLE IF NOT EXISTS account_deletion_requests (
    id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'Opaque public request UUID',
    identifier_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'Domain-separated keyed digest of normalized account email',
    requester_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'Domain-separated keyed digest of requester network address',
    member_id BIGINT NULL
        COMMENT 'Internal binding; cleared after terminal processing and never exposed publicly',
    observed_session_generation BIGINT NULL
        COMMENT 'Session generation captured before external verification',
    manual_review_required BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'Provider-aware support is required; this row cannot authorize cleanup',
    status VARCHAR(40) NOT NULL,
    verification_token_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    verification_attempt_count INT NOT NULL DEFAULT 0,
    verification_expires_at DATETIME(6) NULL,
    deletion_grant_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    deletion_grant_expires_at DATETIME(6) NULL,
    processing_started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    failure_code VARCHAR(40) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    retention_expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_account_deletion_requests_status_expiry (status, verification_expires_at),
    INDEX idx_account_deletion_requests_retention (retention_expires_at),
    INDEX idx_account_deletion_requests_processing (status, processing_started_at)
) COMMENT='Login-free account deletion verification and single-use grant state';
