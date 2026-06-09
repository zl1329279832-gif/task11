package com.clinic.appointment.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class EquipmentDeactivateRequest {
    @NotNull(message = "设备ID不能为空")
    private Long equipmentId;
    @NotNull(message = "生效日期不能为空")
    private LocalDate effectiveDate;
    private String reason;
}
