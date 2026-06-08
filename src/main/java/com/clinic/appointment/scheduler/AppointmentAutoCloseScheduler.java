package com.clinic.appointment.scheduler;

import com.clinic.appointment.config.AppointmentConfig;
import com.clinic.appointment.mapper.AppointmentMapper;
import com.clinic.appointment.mapper.ScheduleMapper;
import com.clinic.appointment.mapper.SlotMapper;
import com.clinic.appointment.model.entity.Appointment;
import com.clinic.appointment.model.entity.Schedule;
import com.clinic.appointment.model.entity.Slot;
import com.clinic.appointment.model.enums.AuditAction;
import com.clinic.appointment.service.WaitlistService;
import com.clinic.appointment.util.AuditUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class AppointmentAutoCloseScheduler {

    private final AppointmentMapper appointmentMapper;
    private final SlotMapper slotMapper;
    private final ScheduleMapper scheduleMapper;
    private final WaitlistService waitlistService;
    private final AppointmentConfig config;
    private final AuditUtil auditUtil;

    /**
     * Auto-cancel expired BOOKED appointments where the patient did not check in
     * within the configured timeout window after the slot start time.
     * Runs every 5 minutes.
     */
    @Scheduled(cron = "0 */5 * * * *")
    public void autoCancelExpiredAppointments() {
        log.info("Starting auto-cancel expired appointments task...");
        LocalDate today = LocalDate.now();
        List<Schedule> todaySchedules = scheduleMapper.selectByDateRange(today, today);
        int cancelled = 0;

        for (Schedule schedule : todaySchedules) {
            List<Appointment> appointments = appointmentMapper.selectActiveByScheduleId(schedule.getId());
            for (Appointment appt : appointments) {
                if (!"BOOKED".equals(appt.getStatus())) {
                    continue;
                }
                Slot slot = slotMapper.selectById(appt.getSlotId());
                if (slot != null && LocalTime.now().isAfter(slot.getStartTime().plusMinutes(config.getAutoCancelMinutes()))) {
                    // Update appointment status to CANCELLED
                    appointmentMapper.updateStatus(appt.getId(), "CANCELLED");
                    appt.setCancelReason("Auto-cancelled: check-in timeout");
                    appt.setCancelledAt(LocalDateTime.now());
                    appointmentMapper.update(appt);

                    // Release slot back to AVAILABLE
                    slotMapper.updateStatus(slot.getId(), "AVAILABLE", "BOOKED", slot.getVersion());

                    // Decrement schedule booked count
                    scheduleMapper.decrementBookedCount(schedule.getId());

                    // Audit log
                    auditUtil.log("APPOINTMENT", appt.getId(), AuditAction.CANCEL, "SYSTEM",
                            "Auto-cancelled due to check-in timeout");

                    // Try to fill from waitlist
                    waitlistService.tryFillFromWaitlist(schedule.getId());

                    cancelled++;
                }
            }
        }

        log.info("Auto-cancelled {} expired appointments", cancelled);
    }
}
