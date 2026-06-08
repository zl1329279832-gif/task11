package com.clinic.appointment.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class BookRequest {
    @NotNull(message = "患者ID不能为空")
    private Long patientId;
    private String patientName;
    @NotNull(message = "号源ID不能为空")
    private Long slotId;
}
