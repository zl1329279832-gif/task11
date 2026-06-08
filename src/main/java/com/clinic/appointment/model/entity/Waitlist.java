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
public class Waitlist {

    private Long id;
    private String patientId;
    private String patientName;
    private Long scheduleId;
    private Long doctorId;
    private Long departmentId;
    private LocalDate scheduleDate;
    private String period;
    private Integer queuePosition;
    private String status;
    private LocalDateTime offeredAt;
    private LocalDateTime expiredAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
