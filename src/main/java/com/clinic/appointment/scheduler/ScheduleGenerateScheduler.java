package com.clinic.appointment.scheduler;

import com.clinic.appointment.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduleGenerateScheduler {

    private final ScheduleService scheduleService;

    /**
     * Auto-generate schedules for the next 7 days.
     * Runs daily at 2:00 AM.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void autoGenerateSchedules() {
        log.info("Starting auto-generate schedules task...");
        LocalDate startDate = LocalDate.now();
        LocalDate endDate = startDate.plusDays(7);
        int count = scheduleService.generateSchedules(startDate, endDate);
        log.info("Auto-generated {} schedules for date range {} to {}", count, startDate, endDate);
    }
}
