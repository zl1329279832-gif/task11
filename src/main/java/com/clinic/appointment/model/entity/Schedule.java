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
public class Schedule {

    private Long id;
    private Long doctorId;
    private LocalDate scheduleDate;
    private String period;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer totalSlots;
    private Integer bookedCount;
    private Integer extraSlots;
    private String status;
    private Long templateId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
