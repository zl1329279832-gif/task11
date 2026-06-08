package com.clinic.appointment.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Suspension {

    private Long id;
    private Long doctorId;
    private Long scheduleId;
    private LocalDate suspendDate;
    private String period;
    private String reason;
    private String actionTaken;
    private Long targetDoctorId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
