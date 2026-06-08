package com.clinic.appointment.domain.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class ScheduleGenerateRequest {
    private Long doctorId;
    private LocalDate startDate;
    private LocalDate endDate;
}
