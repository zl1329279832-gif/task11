package com.clinic.appointment.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RescheduleRequest {
    @NotNull(message = "预约ID不能为空")
    private Long appointmentId;
    @NotNull(message = "新号源ID不能为空")
    private Long newSlotId;
    private String reason;
}
