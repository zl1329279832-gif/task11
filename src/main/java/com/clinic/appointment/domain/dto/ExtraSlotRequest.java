package com.clinic.appointment.domain.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
public class ExtraSlotRequest {
    private Long doctorId;
    private Long scheduleId;
    private LocalDate slotDate;
    private LocalTime slotTime;
    private String reason;
}
