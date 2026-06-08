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
public class Appointment {

    private Long id;
    private String patientId;
    private String patientName;
    private Long slotId;
    private Long scheduleId;
    private Long doctorId;
    private Long departmentId;
    private LocalDate scheduleDate;
    private String period;
    private Integer seqNum;
    private String status;
    private String source;
    private Long originalAppointmentId;
    private String cancelReason;
    private LocalDateTime checkedInAt;
    private LocalDateTime cancelledAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
