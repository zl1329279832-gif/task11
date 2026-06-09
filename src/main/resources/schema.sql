-- ================================================================
-- 门诊预约系统数据库设计
-- ================================================================

-- 1. 科室表
CREATE TABLE IF NOT EXISTS department (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL COMMENT '科室名称',
    code        VARCHAR(50)   NOT NULL COMMENT '科室编码',
    description VARCHAR(500)  DEFAULT '' COMMENT '科室描述',
    status      TINYINT       NOT NULL DEFAULT 1 COMMENT '1-启用 0-停用',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='科室';

-- 2. 医生表
CREATE TABLE IF NOT EXISTS doctor (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(50)  NOT NULL COMMENT '医生姓名',
    employee_no   VARCHAR(50)  NOT NULL COMMENT '工号',
    department_id BIGINT       NOT NULL COMMENT '所属科室',
    title         VARCHAR(50)  DEFAULT '' COMMENT '职称',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1-在职 0-离职',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_employee_no (employee_no),
    INDEX idx_department (department_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医生';

-- 3. 排班模板表（周模板）
CREATE TABLE IF NOT EXISTS schedule_template (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    doctor_id     BIGINT      NOT NULL COMMENT '医生ID',
    day_of_week   TINYINT     NOT NULL COMMENT '星期几 1=周一..7=周日',
    time_period   VARCHAR(10) NOT NULL COMMENT '时段 MORNING/AFTERNOON',
    total_slots   INT         NOT NULL DEFAULT 30 COMMENT '总号源数',
    slot_interval INT         NOT NULL DEFAULT 10 COMMENT '每个号源间隔(分钟)',
    start_time    TIME        NOT NULL COMMENT '开始时间',
    status        TINYINT     NOT NULL DEFAULT 1 COMMENT '1-启用 0-停用',
    create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_doctor (doctor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='排班模板';

-- 4. 医生日排班表（从模板生成的具体日期排班）
CREATE TABLE IF NOT EXISTS doctor_schedule (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    doctor_id     BIGINT  NOT NULL,
    department_id BIGINT  NOT NULL,
    schedule_date DATE    NOT NULL COMMENT '排班日期',
    time_period   VARCHAR(10) NOT NULL COMMENT 'MORNING/AFTERNOON',
    total_slots   INT     NOT NULL DEFAULT 0 COMMENT '总号源',
    booked_slots  INT     NOT NULL DEFAULT 0 COMMENT '已预约数',
    extra_slots   INT     NOT NULL DEFAULT 0 COMMENT '加号数',
    status        VARCHAR(20) NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/SUSPENDED/HOLIDAY',
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_doctor_date_period (doctor_id, schedule_date, time_period),
    INDEX idx_date (schedule_date),
    INDEX idx_dept_date (department_id, schedule_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医生日排班';

-- 5. 节假日停诊表
CREATE TABLE IF NOT EXISTS holiday (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    holiday_date  DATE         NOT NULL COMMENT '停诊日期',
    name          VARCHAR(100) DEFAULT '' COMMENT '节假日名称',
    scope         VARCHAR(20)  NOT NULL DEFAULT 'ALL' COMMENT 'ALL/DEPARTMENT',
    department_id BIGINT       DEFAULT NULL COMMENT 'scope=DEPARTMENT时指定科室',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_holiday_date_dept (holiday_date, department_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='节假日停诊';

-- 6. 号源表（每个号源一条记录）
CREATE TABLE IF NOT EXISTS schedule_slot (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id   BIGINT      NOT NULL COMMENT '关联日排班ID',
    doctor_id     BIGINT      NOT NULL,
    department_id BIGINT      NOT NULL,
    slot_date     DATE        NOT NULL,
    slot_time     TIME        NOT NULL COMMENT '号源时间点',
    slot_no       INT         NOT NULL COMMENT '序号',
    status        VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE'
                COMMENT 'AVAILABLE/BOOKED/OCCUPIED/EXPIRED/RELEASED',
    appointment_id BIGINT     DEFAULT NULL COMMENT '关联预约ID',
    is_extra      TINYINT     NOT NULL DEFAULT 0 COMMENT '是否加号',
    version       INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_schedule (schedule_id),
    INDEX idx_doctor_date (doctor_id, slot_date),
    INDEX idx_status (status),
    INDEX idx_appointment (appointment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='号源';

-- 7. 预约表
CREATE TABLE IF NOT EXISTS appointment (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    appointment_no VARCHAR(32)  NOT NULL COMMENT '预约单号',
    patient_id     BIGINT       NOT NULL COMMENT '患者ID',
    patient_name   VARCHAR(50)  NOT NULL DEFAULT '' COMMENT '患者姓名',
    doctor_id      BIGINT       NOT NULL,
    department_id  BIGINT       NOT NULL,
    slot_id        BIGINT       NOT NULL COMMENT '号源ID',
    slot_date      DATE         NOT NULL,
    slot_time      TIME         NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
            COMMENT 'PENDING/CONFIRMED/CANCELLED/RESCHEDULED/CHECKED_IN/MISSED',
    source         VARCHAR(20)  NOT NULL DEFAULT 'ONLINE' COMMENT 'ONLINE/WINDOW/WAITLIST',
    booking_type   VARCHAR(20)  NOT NULL DEFAULT 'SINGLE' COMMENT 'SINGLE/JOINT',
    exam_type_code VARCHAR(50)  DEFAULT NULL COMMENT '检查类型编码(JOINT时必填)',
    original_id    BIGINT       DEFAULT NULL COMMENT '改签时指向原预约ID',
    cancel_reason  VARCHAR(500) DEFAULT '' COMMENT '取消原因',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_appointment_no (appointment_no),
    INDEX idx_patient (patient_id),
    INDEX idx_doctor_date (doctor_id, slot_date),
    INDEX idx_slot (slot_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约';

-- 8. 候补队列表
CREATE TABLE IF NOT EXISTS waitlist (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    patient_id    BIGINT      NOT NULL,
    patient_name  VARCHAR(50) NOT NULL DEFAULT '',
    doctor_id     BIGINT      NOT NULL,
    department_id BIGINT      NOT NULL,
    target_date   DATE        NOT NULL,
    time_period   VARCHAR(10) NOT NULL DEFAULT 'MORNING' COMMENT '期望时段',
    exam_type_code VARCHAR(50) DEFAULT NULL COMMENT '检查类型编码(联合预约候补)',
    priority      INT         NOT NULL DEFAULT 0 COMMENT '优先级(越小越高)',
    status        VARCHAR(20) NOT NULL DEFAULT 'WAITING' COMMENT 'WAITING/FULFILLED/EXPIRED/CANCELLED',
    appointment_id BIGINT     DEFAULT NULL COMMENT '补位成功的预约ID',
    expire_time   DATETIME    DEFAULT NULL COMMENT '过期时间',
    create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_target (doctor_id, target_date, status),
    INDEX idx_patient (patient_id),
    INDEX idx_status_expire (status, expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '候补队列';

-- 9. 医生停诊表
CREATE TABLE IF NOT EXISTS doctor_suspension (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    doctor_id     BIGINT       NOT NULL,
    start_date    DATE         NOT NULL COMMENT '停诊开始日期',
    end_date      DATE         NOT NULL COMMENT '停诊结束日期',
    reason        VARCHAR(500) DEFAULT '' COMMENT '停诊原因',
    migrate_type  VARCHAR(20)  NOT NULL DEFAULT 'CANCEL' COMMENT 'CANCEL/MIGRATE',
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/COMPLETED',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_doctor (doctor_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医生停诊';

-- 10. 资源表（诊室/设备/护理）
CREATE TABLE IF NOT EXISTS resource (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(100)  NOT NULL COMMENT '资源名称',
    code          VARCHAR(50)   NOT NULL COMMENT '资源编码',
    type          VARCHAR(20)   NOT NULL COMMENT 'ROOM/EQUIPMENT/NURSING',
    department_id BIGINT        DEFAULT NULL COMMENT '所属科室(NULL表示全院共享)',
    capacity      INT           NOT NULL DEFAULT 1 COMMENT '并发容量',
    description   VARCHAR(500)  DEFAULT '' COMMENT '描述',
    status        TINYINT       NOT NULL DEFAULT 1 COMMENT '1-启用 0-停用',
    create_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_code (code),
    INDEX idx_type (type),
    INDEX idx_dept (department_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资源(诊室/设备/护理)';

-- 11. 资源时段表（CAS保护）
CREATE TABLE IF NOT EXISTS resource_slot (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_id    BIGINT       NOT NULL COMMENT '资源ID',
    resource_type  VARCHAR(20)  NOT NULL COMMENT 'ROOM/EQUIPMENT/NURSING',
    slot_date      DATE         NOT NULL COMMENT '日期',
    start_time     TIME         NOT NULL COMMENT '开始时间',
    end_time       TIME         NOT NULL COMMENT '结束时间',
    capacity       INT          NOT NULL DEFAULT 1 COMMENT '此时段最大并发数',
    booked_count   INT          NOT NULL DEFAULT 0 COMMENT '已预约数',
    status         VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE'
                   COMMENT 'AVAILABLE/FULL/DISABLED/MAINTENANCE',
    version        INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_resource_date (resource_id, slot_date),
    INDEX idx_type_date (resource_type, slot_date),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资源时段(CAS保护)';

-- 12. 检查类型表（定义资源需求）
CREATE TABLE IF NOT EXISTS exam_type (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                VARCHAR(100)  NOT NULL COMMENT '检查类型名称',
    code                VARCHAR(50)   NOT NULL COMMENT '类型编码',
    need_room           TINYINT       NOT NULL DEFAULT 0 COMMENT '是否需要诊室',
    need_equipment      TINYINT       NOT NULL DEFAULT 0 COMMENT '是否需要设备',
    need_nursing        TINYINT       NOT NULL DEFAULT 0 COMMENT '是否需要护理',
    equipment_code      VARCHAR(50)   DEFAULT NULL COMMENT '指定设备编码(NULL=任意)',
    room_code           VARCHAR(50)   DEFAULT NULL COMMENT '指定诊室编码(NULL=任意)',
    patient_daily_limit INT           DEFAULT NULL COMMENT '患者每日此类检查上限',
    status              TINYINT       NOT NULL DEFAULT 1 COMMENT '1-启用 0-停用',
    create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='检查类型定义';

-- 13. 预约-资源关联表
CREATE TABLE IF NOT EXISTS appointment_resource (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    appointment_id   BIGINT       NOT NULL COMMENT '预约ID',
    resource_slot_id BIGINT       NOT NULL COMMENT '资源时段ID',
    resource_id      BIGINT       NOT NULL COMMENT '资源ID',
    resource_type    VARCHAR(20)  NOT NULL COMMENT 'ROOM/EQUIPMENT/NURSING',
    status           VARCHAR(20)  NOT NULL DEFAULT 'BOOKED' COMMENT 'BOOKED/RELEASED',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_appointment (appointment_id),
    INDEX idx_resource_slot (resource_slot_id),
    INDEX idx_resource (resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约-资源关联';

-- 14. 审计日志表
CREATE TABLE IF NOT EXISTS audit_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation     VARCHAR(50)   NOT NULL COMMENT '操作类型',
    entity_type   VARCHAR(50)   NOT NULL DEFAULT '' COMMENT '实体类型',
    entity_id     BIGINT        DEFAULT NULL COMMENT '实体ID',
    operator_id   BIGINT        DEFAULT NULL COMMENT '操作人ID',
    operator_name VARCHAR(50)   DEFAULT '' COMMENT '操作人姓名',
    detail        TEXT          COMMENT '详情JSON',
    ip            VARCHAR(50)   DEFAULT '' COMMENT 'IP',
    create_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_entity (entity_type, entity_id),
    INDEX idx_operation (operation),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计日志';
