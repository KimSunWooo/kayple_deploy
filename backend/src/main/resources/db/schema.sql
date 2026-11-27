-- Idempotent schema bootstrap. No DROP — existing reservation/outbox rows are preserved across restarts.
CREATE TABLE IF NOT EXISTS reservations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_name VARCHAR(100) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    reservation_date DATE NOT NULL,
    reservation_hour INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    active_slot TINYINT
        GENERATED ALWAYS AS (CASE WHEN status = 'CONFIRMED' THEN 1 ELSE NULL END) STORED,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_at TIMESTAMP NULL,
    CONSTRAINT uk_active_slot UNIQUE (reservation_date, reservation_hour, active_slot),
    CONSTRAINT chk_hour CHECK (reservation_hour IN (10, 11, 13, 14, 15, 16, 17)),
    CONSTRAINT chk_status CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    INDEX idx_reservations_phone (phone),
    INDEX idx_reservations_date (reservation_date)
);

CREATE TABLE IF NOT EXISTS sms_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_id BIGINT NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    message VARCHAR(200) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processing_started_at TIMESTAMP NULL,
    first_attempt_at TIMESTAMP NULL,
    last_error VARCHAR(500) NULL,
    provider_message_id VARCHAR(100) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP NULL,
    CONSTRAINT uk_reservation_event UNIQUE (reservation_id, event_type),
    CONSTRAINT uk_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_sms_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id),
    CONSTRAINT chk_event_type CHECK (event_type IN ('BOOKED', 'CANCELLED')),
    CONSTRAINT chk_sms_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED')),
    INDEX idx_sms_claim (status, next_retry_at),
    INDEX idx_sms_processing (status, processing_started_at)
);
