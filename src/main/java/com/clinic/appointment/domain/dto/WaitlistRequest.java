package com.clinic.appointment.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class WaitlistRequest {
    @NotNull(message = "患者ID不能为空")
    private Long patientId;
    private String patientName;
    @NotNull(message = "医生ID不能为空")
    private Long doctorId;
    @NotNull(message = "科室ID不能为空")
    private Long departmentId;
    @NotNull(message = "目标日期不能为空")
    private LocalDate targetDate;
    private String timePeriod = "MORNING";
    private String examTypeCode;
}
