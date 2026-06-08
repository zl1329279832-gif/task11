package com.clinic.appointment.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CancelRequest {
    @NotNull(message = "预约ID不能为空")
    private Long appointmentId;
    private String reason;
}
