package com.clinic.appointment.service;

/**
 * 审计日志服务
 */
public interface AuditService {

    /**
     * 记录审计日志
     * @param operation   操作类型 (BOOK/CANCEL/RESCHEDULE/CHECKIN/SUSPEND等)
     * @param entityType  实体类型 (APPOINTMENT/SLOT/SCHEDULE等)
     * @param entityId    实体ID
     * @param detail      详情JSON
     */
    void log(String operation, String entityType, Long entityId, String detail);

    void log(String operation, String entityType, Long entityId,
             Long operatorId, String operatorName, String detail);
}
