-- =============================================
-- Clinic Appointment System - Database Schema
-- H2-compatible version for testing
-- NOTE: H2 uses global constraint/index names,
--       so all names are prefixed with table abbreviation.
-- =============================================

CREATE TABLE IF NOT EXISTS department (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100)    NOT NULL,
    code            VARCHAR(50)     NOT NULL,
    description     VARCHAR(500)    DEFAULT NULL,
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_dept_code (code)
);

CREATE TABLE IF NOT EXISTS doctor (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100)    NOT NULL,
    code            VARCHAR(50)     NOT NULL,
    title           VARCHAR(50)     DEFAULT NULL,
    department_id   BIGINT          NOT NULL,
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_doc_code (code),
    INDEX idx_doc_department (department_id)
);

-- Schedule template: defines recurring weekly patterns
CREATE TABLE IF NOT EXISTS schedule_template (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    doctor_id       BIGINT          NOT NULL,
    day_of_week     TINYINT         NOT NULL,
    period          VARCHAR(20)     NOT NULL,
    start_time      TIME            NOT NULL,
    end_time        TIME            NOT NULL,
    max_slots       INT             NOT NULL,
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_tmpl_doctor (doctor_id),
    UNIQUE KEY uk_tmpl_doctor_day_period (doctor_id, day_of_week, period)
);

-- Generated schedule instances (one per doctor per date per period)
CREATE TABLE IF NOT EXISTS schedule (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    doctor_id       BIGINT          NOT NULL,
    schedule_date   DATE            NOT NULL,
    period          VARCHAR(20)     NOT NULL,
    start_time      TIME            NOT NULL,
    end_time        TIME            NOT NULL,
    total_slots     INT             NOT NULL,
    booked_count    INT             NOT NULL DEFAULT 0,
    extra_slots     INT             NOT NULL DEFAULT 0,
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    template_id     BIGINT          DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sched_doctor_date_period (doctor_id, schedule_date, period),
    INDEX idx_sched_date (schedule_date),
    INDEX idx_sched_status (status)
);

-- Individual appointment slots
CREATE TABLE IF NOT EXISTS slot (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id     BIGINT          NOT NULL,
    doctor_id       BIGINT          NOT NULL,
    schedule_date   DATE            NOT NULL,
    period          VARCHAR(20)     NOT NULL,
    seq_num         INT             NOT NULL,
    start_time      TIME            NOT NULL,
    end_time        TIME            NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'AVAILABLE',
    is_extra        BOOLEAN         NOT NULL DEFAULT FALSE,
    version         INT             NOT NULL DEFAULT 0,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_slot_schedule (schedule_id),
    INDEX idx_slot_doctor_date (doctor_id, schedule_date),
    INDEX idx_slot_status (status)
);

-- Appointment records
CREATE TABLE IF NOT EXISTS appointment (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    patient_id      VARCHAR(64)     NOT NULL,
    patient_name    VARCHAR(100)    NOT NULL,
    slot_id         BIGINT          NOT NULL,
    schedule_id     BIGINT          NOT NULL,
    doctor_id       BIGINT          NOT NULL,
    department_id   BIGINT          NOT NULL,
    schedule_date   DATE            NOT NULL,
    period          VARCHAR(20)     NOT NULL,
    seq_num         INT             NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'BOOKED',
    source          VARCHAR(20)     NOT NULL DEFAULT 'NORMAL',
    original_appointment_id BIGINT  DEFAULT NULL,
    cancel_reason   VARCHAR(500)    DEFAULT NULL,
    checked_in_at   DATETIME        DEFAULT NULL,
    cancelled_at    DATETIME        DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_appt_patient (patient_id),
    INDEX idx_appt_slot (slot_id),
    INDEX idx_appt_schedule (schedule_id),
    INDEX idx_appt_doctor_date (doctor_id, schedule_date),
    INDEX idx_appt_status (status)
);

-- Waitlist queue
CREATE TABLE IF NOT EXISTS waitlist (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    patient_id      VARCHAR(64)     NOT NULL,
    patient_name    VARCHAR(100)    NOT NULL,
    schedule_id     BIGINT          NOT NULL,
    doctor_id       BIGINT          NOT NULL,
    department_id   BIGINT          NOT NULL,
    schedule_date   DATE            NOT NULL,
    period          VARCHAR(20)     NOT NULL,
    queue_position  INT             NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'WAITING',
    offered_at      DATETIME        DEFAULT NULL,
    expired_at      DATETIME        DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_wl_schedule_status (schedule_id, status),
    INDEX idx_wl_patient (patient_id)
);

-- Holiday / closure calendar
CREATE TABLE IF NOT EXISTS holiday (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    holiday_date    DATE            NOT NULL,
    name            VARCHAR(100)    NOT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_holiday_date (holiday_date)
);

-- Suspension records (doctor-level stop-clinic)
CREATE TABLE IF NOT EXISTS suspension (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    doctor_id       BIGINT          NOT NULL,
    schedule_id     BIGINT          DEFAULT NULL,
    suspend_date    DATE            NOT NULL,
    period          VARCHAR(20)     DEFAULT NULL,
    reason          VARCHAR(500)    NOT NULL,
    action_taken    VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    target_doctor_id BIGINT         DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_susp_doctor_date (doctor_id, suspend_date)
);

-- Audit log
CREATE TABLE IF NOT EXISTS audit_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    entity_type     VARCHAR(50)     NOT NULL,
    entity_id       BIGINT          NOT NULL,
    action          VARCHAR(50)     NOT NULL,
    operator        VARCHAR(100)    DEFAULT NULL,
    detail          VARCHAR(2000)   DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_entity (entity_type, entity_id),
    INDEX idx_audit_action (action),
    INDEX idx_audit_time (created_at)
);
