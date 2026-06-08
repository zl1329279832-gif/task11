package com.clinic.appointment.util;

import com.clinic.appointment.mapper.AuditLogMapper;
import com.clinic.appointment.model.entity.AuditLog;
import com.clinic.appointment.model.enums.AuditAction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class AuditUtil {

    private final AuditLogMapper auditLogMapper;

    public void log(String entityType, Long entityId, AuditAction action, String operator, String detail) {
        AuditLog auditLog = AuditLog.builder()
                .entityType(entityType)
                .entityId(entityId)
                .action(action.name())
                .operator(operator)
                .detail(detail)
                .createdAt(LocalDateTime.now())
                .build();
        auditLogMapper.insert(auditLog);
    }
}
