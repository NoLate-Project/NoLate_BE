ALTER TABLE schedules
    ADD COLUMN route_setup_required BOOLEAN NOT NULL DEFAULT FALSE
    COMMENT 'Whether route setup is still required after quick share';
