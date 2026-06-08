package com.clinic.appointment.service;

import com.clinic.appointment.model.entity.Schedule;
import java.time.LocalDate;
import java.util.List;

public interface ScheduleService {
    /**
     * Generate schedules from templates for a date range.
     * Skips holidays and already-existing schedules.
     */
    int generateSchedules(LocalDate startDate, LocalDate endDate);

    Schedule getById(Long id);
    List<Schedule> getByDoctorAndDate(Long doctorId, LocalDate date);
    List<Schedule> getByDateRange(LocalDate start, LocalDate end);
    List<Schedule> getByDoctorAndDateRange(Long doctorId, LocalDate start, LocalDate end);

    /** Add extra slots to an existing schedule */
    void addExtraSlots(Long scheduleId, int count);

    /** Suspend a schedule (mark SUSPENDED) */
    void suspend(Long scheduleId);
}
