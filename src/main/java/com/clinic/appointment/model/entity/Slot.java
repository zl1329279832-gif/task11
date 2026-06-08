package com.clinic.appointment.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Slot {

    private Long id;
    private Long scheduleId;
    private Long doctorId;
    private LocalDate scheduleDate;
    private String period;
    private Integer seqNum;
    private LocalTime startTime;
    private LocalTime endTime;
    private String status;
    private Boolean isExtra;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
