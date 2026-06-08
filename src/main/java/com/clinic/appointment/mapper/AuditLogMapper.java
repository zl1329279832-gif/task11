package com.clinic.appointment.mapper;

import com.clinic.appointment.model.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AuditLogMapper {

    int insert(AuditLog auditLog);

    List<AuditLog> selectByEntity(@Param("entityType") String entityType,
                                  @Param("entityId") Long entityId);

    List<AuditLog> selectByAction(@Param("action") String action);

    List<AuditLog> selectRecent(@Param("limit") Integer limit);
}
