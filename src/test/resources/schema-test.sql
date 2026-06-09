-- ================================================================
-- 门诊预约系统测试数据库Schema（H2兼容版本）
-- ================================================================

CREATE TABLE IF NOT EXISTS department (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,
    code        VARCHAR(50)   NOT NULL,
    description VARCHAR(500)  DEFAULT '',
    status      TINYINT       NOT NULL DEFAULT 1,
    create_time TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS doctor (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(50)  NOT NULL,
    employee_no   VARCHAR(50)  NOT NULL,
    department_id BIGINT       NOT NULL,
    title         VARCHAR(50)  DEFAULT '',
    status        TINYINT      NOT NULL DEFAULT 1,
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS schedule_template (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    doctor_id     BIGINT      NOT NULL,
    day_of_week   TINYINT     NOT NULL,
    time_period   VARCHAR(10) NOT NULL,
    total_slots   INT         NOT NULL DEFAULT 30,
    slot_interval INT         NOT NULL DEFAULT 10,
    start_time    TIME        NOT NULL,
    status        TINYINT     NOT NULL DEFAULT 1,
    create_time   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS doctor_schedule (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    doctor_id     BIGINT  NOT NULL,
    department_id BIGINT  NOT NULL,
    schedule_date DATE    NOT NULL,
    time_period   VARCHAR(10) NOT NULL,
    total_slots   INT     NOT NULL DEFAULT 0,
    booked_slots  INT     NOT NULL DEFAULT 0,
    extra_slots   INT     NOT NULL DEFAULT 0,
    status        VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    create_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS holiday (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    holiday_date  DATE         NOT NULL,
    name          VARCHAR(100) DEFAULT '',
    scope         VARCHAR(20)  NOT NULL DEFAULT 'ALL',
    department_id BIGINT       DEFAULT NULL,
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS schedule_slot (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id   BIGINT      NOT NULL,
    doctor_id     BIGINT      NOT NULL,
    department_id BIGINT      NOT NULL,
    slot_date     DATE        NOT NULL,
    slot_time     TIME        NOT NULL,
    slot_no       INT         NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    appointment_id BIGINT     DEFAULT NULL,
    is_extra      TINYINT     NOT NULL DEFAULT 0,
    version       INT         NOT NULL DEFAULT 0,
    create_time   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS appointment (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    appointment_no VARCHAR(32)  NOT NULL,
    patient_id     BIGINT       NOT NULL,
    patient_name   VARCHAR(50)  NOT NULL DEFAULT '',
    doctor_id      BIGINT       NOT NULL,
    department_id  BIGINT       NOT NULL,
    slot_id        BIGINT       NOT NULL,
    slot_date      DATE         NOT NULL,
    slot_time      TIME         NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    source         VARCHAR(20)  NOT NULL DEFAULT 'ONLINE',
    booking_type   VARCHAR(20)  NOT NULL DEFAULT 'SINGLE',
    exam_type_code VARCHAR(50)  DEFAULT NULL,
    original_id    BIGINT       DEFAULT NULL,
    cancel_reason  VARCHAR(500) DEFAULT '',
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS waitlist (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    patient_id    BIGINT      NOT NULL,
    patient_name  VARCHAR(50) NOT NULL DEFAULT '',
    doctor_id     BIGINT      NOT NULL,
    department_id BIGINT      NOT NULL,
    target_date   DATE        NOT NULL,
    time_period   VARCHAR(10) NOT NULL DEFAULT 'MORNING',
    exam_type_code VARCHAR(50) DEFAULT NULL,
    priority      INT         NOT NULL DEFAULT 0,
    status        VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    appointment_id BIGINT     DEFAULT NULL,
    expire_time   TIMESTAMP   DEFAULT NULL,
    create_time   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS doctor_suspension (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    doctor_id     BIGINT       NOT NULL,
    start_date    DATE         NOT NULL,
    end_date      DATE         NOT NULL,
    reason        VARCHAR(500) DEFAULT '',
    migrate_type  VARCHAR(20)  NOT NULL DEFAULT 'CANCEL',
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS audit_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation     VARCHAR(50)   NOT NULL,
    entity_type   VARCHAR(50)   NOT NULL DEFAULT '',
    entity_id     BIGINT        DEFAULT NULL,
    operator_id   BIGINT        DEFAULT NULL,
    operator_name VARCHAR(50)   DEFAULT '',
    detail        TEXT,
    ip            VARCHAR(50)   DEFAULT '',
    create_time   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS resource (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(100)  NOT NULL,
    code          VARCHAR(50)   NOT NULL,
    type          VARCHAR(20)   NOT NULL,
    department_id BIGINT        DEFAULT NULL,
    capacity      INT           NOT NULL DEFAULT 1,
    description   VARCHAR(500)  DEFAULT '',
    status        TINYINT       NOT NULL DEFAULT 1,
    create_time   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS resource_slot (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_id    BIGINT       NOT NULL,
    resource_type  VARCHAR(20)  NOT NULL,
    slot_date      DATE         NOT NULL,
    start_time     TIME         NOT NULL,
    end_time       TIME         NOT NULL,
    capacity       INT          NOT NULL DEFAULT 1,
    booked_count   INT          NOT NULL DEFAULT 0,
    status         VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',
    version        INT          NOT NULL DEFAULT 0,
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS exam_type (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                VARCHAR(100)  NOT NULL,
    code                VARCHAR(50)   NOT NULL,
    need_room           TINYINT       NOT NULL DEFAULT 0,
    need_equipment      TINYINT       NOT NULL DEFAULT 0,
    need_nursing        TINYINT       NOT NULL DEFAULT 0,
    equipment_code      VARCHAR(50)   DEFAULT NULL,
    room_code           VARCHAR(50)   DEFAULT NULL,
    patient_daily_limit INT           DEFAULT NULL,
    status              TINYINT       NOT NULL DEFAULT 1,
    create_time         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS appointment_resource (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    appointment_id   BIGINT       NOT NULL,
    resource_slot_id BIGINT       NOT NULL,
    resource_id      BIGINT       NOT NULL,
    resource_type    VARCHAR(20)  NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'BOOKED',
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
