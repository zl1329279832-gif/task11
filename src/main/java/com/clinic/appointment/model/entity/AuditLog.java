package com.clinic.appointment.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    private Long id;
    private String entityType;
    private Long entityId;
    private String action;
    private String operator;
    private String detail;
    private LocalDateTime createdAt;
}
