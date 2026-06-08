package com.clinic.appointment.service.impl;

import com.clinic.appointment.mapper.AuditLogMapper;
import com.clinic.appointment.model.entity.AuditLog;
import com.clinic.appointment.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {
    private final AuditLogMapper auditLogMapper;

    @Override
    public List<AuditLog> getByEntity(String entityType, Long entityId) {
        return auditLogMapper.selectByEntity(entityType, entityId);
    }

    @Override
    public List<AuditLog> getRecent(Integer limit) {
        return auditLogMapper.selectRecent(limit);
    }
}
