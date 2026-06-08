package com.clinic.appointment.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleTemplate {

    private Long id;
    private Long doctorId;
    private Integer dayOfWeek;
    private String period;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer maxSlots;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
