package com.clinic.appointment.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    // ── 常用工厂方法 ──────────────────────────────────────

    public static BusinessException of(String code, String message) {
        return new BusinessException(code, message);
    }

    public static BusinessException slotNotAvailable() {
        return new BusinessException("SLOT_NOT_AVAILABLE", "号源不可用或已被占用");
    }

    public static BusinessException appointmentNotFound() {
        return new BusinessException("APPOINTMENT_NOT_FOUND", "预约记录不存在");
    }

    public static BusinessException invalidStatusTransition(String from, String to) {
        return new BusinessException("INVALID_STATUS_TRANSITION",
                "不允许从 " + from + " 转换到 " + to);
    }

    public static BusinessException lockAcquireFailed() {
        return new BusinessException("LOCK_ACQUIRE_FAILED", "系统繁忙，请稍后重试");
    }

    public static BusinessException scheduleNotFound() {
        return new BusinessException("SCHEDULE_NOT_FOUND", "排班不存在");
    }

    public static BusinessException waitlistFull() {
        return new BusinessException("WAITLIST_FULL", "候补队列已满");
    }

    public static BusinessException resourceUnavailable(String resourceType) {
        return new BusinessException("RESOURCE_UNAVAILABLE", resourceType + " 不可用或已被占用");
    }

    public static BusinessException resourceCasFailed(String resourceType) {
        return new BusinessException("RESOURCE_CAS_FAILED", resourceType + " 锁定失败（并发冲突），请重试");
    }

    public static BusinessException patientLimitExceeded(int max) {
        return new BusinessException("PATIENT_LIMIT_EXCEEDED", "患者当日预约数已达上限(" + max + ")");
    }

    public static BusinessException notJointAppointment() {
        return new BusinessException("NOT_JOINT_APPOINTMENT", "非联合预约不可使用联合操作");
    }
}
