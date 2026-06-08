-- =============================================
-- Clinic Appointment System - Database Schema
-- =============================================

CREATE TABLE IF NOT EXISTS department (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100)    NOT NULL COMMENT '科室名称',
    code            VARCHAR(50)     NOT NULL COMMENT '科室编码',
    description     VARCHAR(500)    DEFAULT NULL,
    enabled         TINYINT(1)      NOT NULL DEFAULT 1,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_code (code)
);

CREATE TABLE IF NOT EXISTS doctor (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100)    NOT NULL COMMENT '医生姓名',
    code            VARCHAR(50)     NOT NULL COMMENT '工号',
    title           VARCHAR(50)     DEFAULT NULL COMMENT '职称',
    department_id   BIGINT          NOT NULL,
    enabled         TINYINT(1)      NOT NULL DEFAULT 1,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_code (code),
    INDEX idx_department (department_id)
);

-- Schedule template: defines recurring weekly patterns
CREATE TABLE IF NOT EXISTS schedule_template (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    doctor_id       BIGINT          NOT NULL,
    day_of_week     TINYINT         NOT NULL COMMENT '1=Mon...7=Sun',
    period          VARCHAR(20)     NOT NULL COMMENT 'AM / PM',
    start_time      TIME            NOT NULL,
    end_time        TIME            NOT NULL,
    max_slots       INT             NOT NULL COMMENT '最大号源数',
    enabled         TINYINT(1)      NOT NULL DEFAULT 1,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_doctor (doctor_id),
    UNIQUE KEY uk_doctor_day_period (doctor_id, day_of_week, period)
);

-- Generated schedule instances (one per doctor per date per period)
CREATE TABLE IF NOT EXISTS schedule (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    doctor_id       BIGINT          NOT NULL,
    schedule_date   DATE            NOT NULL,
    period          VARCHAR(20)     NOT NULL COMMENT 'AM / PM',
    start_time      TIME            NOT NULL,
    end_time        TIME            NOT NULL,
    total_slots     INT             NOT NULL,
    booked_count    INT             NOT NULL DEFAULT 0,
    extra_slots     INT             NOT NULL DEFAULT 0 COMMENT '临时加号数',
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / SUSPENDED',
    template_id     BIGINT          DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_doctor_date_period (doctor_id, schedule_date, period),
    INDEX idx_date (schedule_date),
    INDEX idx_status (status)
);

-- Individual appointment slots
CREATE TABLE IF NOT EXISTS slot (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id     BIGINT          NOT NULL,
    doctor_id       BIGINT          NOT NULL,
    schedule_date   DATE            NOT NULL,
    period          VARCHAR(20)     NOT NULL,
    seq_num         INT             NOT NULL COMMENT '序号',
    start_time      TIME            NOT NULL,
    end_time        TIME            NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'AVAILABLE'
                    COMMENT 'AVAILABLE / LOCKED / BOOKED / CHECKED_IN / COMPLETED / CANCELLED / PASSED / EXTRA',
    is_extra        TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否加号',
    version         INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_schedule (schedule_id),
    INDEX idx_doctor_date (doctor_id, schedule_date),
    INDEX idx_status (status)
);

-- Appointment records
CREATE TABLE IF NOT EXISTS appointment (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    patient_id      VARCHAR(64)     NOT NULL COMMENT '患者ID',
    patient_name    VARCHAR(100)    NOT NULL,
    slot_id         BIGINT          NOT NULL,
    schedule_id     BIGINT          NOT NULL,
    doctor_id       BIGINT          NOT NULL,
    department_id   BIGINT          NOT NULL,
    schedule_date   DATE            NOT NULL,
    period          VARCHAR(20)     NOT NULL,
    seq_num         INT             NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'BOOKED'
                    COMMENT 'BOOKED / CHECKED_IN / COMPLETED / CANCELLED / RESCHEDULED / PASSED',
    source          VARCHAR(20)     NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL / WAITLIST / EXTRA',
    original_appointment_id BIGINT  DEFAULT NULL COMMENT '改签来源',
    cancel_reason   VARCHAR(500)    DEFAULT NULL,
    checked_in_at   DATETIME        DEFAULT NULL,
    cancelled_at    DATETIME        DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_patient (patient_id),
    INDEX idx_slot (slot_id),
    INDEX idx_schedule (schedule_id),
    INDEX idx_doctor_date (doctor_id, schedule_date),
    INDEX idx_status (status)
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
    queue_position  INT             NOT NULL COMMENT '队列位置',
    status          VARCHAR(20)     NOT NULL DEFAULT 'WAITING'
                    COMMENT 'WAITING / OFFERED / CONVERTED / EXPIRED / CANCELLED',
    offered_at      DATETIME        DEFAULT NULL,
    expired_at      DATETIME        DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_schedule_status (schedule_id, status),
    INDEX idx_patient (patient_id)
);

-- Holiday / closure calendar
CREATE TABLE IF NOT EXISTS holiday (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    holiday_date    DATE            NOT NULL,
    name            VARCHAR(100)    NOT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_date (holiday_date)
);

-- Suspension records (doctor-level stop-clinic)
CREATE TABLE IF NOT EXISTS suspension (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    doctor_id       BIGINT          NOT NULL,
    schedule_id     BIGINT          DEFAULT NULL,
    suspend_date    DATE            NOT NULL,
    period          VARCHAR(20)     DEFAULT NULL COMMENT 'NULL=全天',
    reason          VARCHAR(500)    NOT NULL,
    action_taken    VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                    COMMENT 'PENDING / CANCELLED_ALL / MIGRATED',
    target_doctor_id BIGINT         DEFAULT NULL COMMENT '迁移目标医生',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_doctor_date (doctor_id, suspend_date)
);

-- Audit log
CREATE TABLE IF NOT EXISTS audit_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    entity_type     VARCHAR(50)     NOT NULL COMMENT 'APPOINTMENT / SLOT / SCHEDULE / WAITLIST',
    entity_id       BIGINT          NOT NULL,
    action          VARCHAR(50)     NOT NULL COMMENT 'BOOK / CANCEL / RESCHEDULE / CHECK_IN / PASS / WAITLIST_CONVERT / SUSPEND / MIGRATE / EXTRA_SLOT',
    operator        VARCHAR(100)    DEFAULT NULL,
    detail          VARCHAR(2000)   DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_entity (entity_type, entity_id),
    INDEX idx_action (action),
    INDEX idx_time (created_at)
);
