package com.clinic.appointment.service;

import com.clinic.appointment.model.entity.AuditLog;
import java.util.List;

public interface AuditService {
    List<AuditLog> getByEntity(String entityType, Long entityId);
    List<AuditLog> getRecent(Integer limit);
}
