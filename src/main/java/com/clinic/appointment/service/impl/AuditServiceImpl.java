package com.clinic.appointment.service.impl;

import com.clinic.appointment.domain.entity.AuditLog;
import com.clinic.appointment.mapper.AuditLogMapper;
import com.clinic.appointment.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private final AuditLogMapper auditLogMapper;

    @Override
    @Async
    public void log(String operation, String entityType, Long entityId, String detail) {
        log(operation, entityType, entityId, null, null, detail);
    }

    @Override
    @Async
    public void log(String operation, String entityType, Long entityId,
                    Long operatorId, String operatorName, String detail) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setOperation(operation);
            auditLog.setEntityType(entityType);
            auditLog.setEntityId(entityId);
            auditLog.setOperatorId(operatorId);
            auditLog.setOperatorName(operatorName != null ? operatorName : "");
            auditLog.setDetail(detail);
            auditLog.setIp("");
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            log.error("审计日志写入失败: op={}, entity={}#{}", operation, entityType, entityId, e);
        }
    }
}
