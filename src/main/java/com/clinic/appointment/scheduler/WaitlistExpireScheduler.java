package com.clinic.appointment.scheduler;

import com.clinic.appointment.mapper.ScheduleMapper;
import com.clinic.appointment.mapper.WaitlistMapper;
import com.clinic.appointment.model.entity.Schedule;
import com.clinic.appointment.model.entity.Waitlist;
import com.clinic.appointment.model.enums.AuditAction;
import com.clinic.appointment.util.AuditUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class WaitlistExpireScheduler {

    private final WaitlistMapper waitlistMapper;
    private final ScheduleMapper scheduleMapper;
    private final AuditUtil auditUtil;

    /**
     * Expire waitlist entries whose schedule date has passed.
     * Covers both WAITING and OFFERED entries for the past 7 days.
     * Runs daily at 11:00 PM.
     */
    @Scheduled(cron = "0 0 23 * * *")
    public void expireOldWaitlistEntries() {
        log.info("Starting expire old waitlist entries task...");
        LocalDate today = LocalDate.now();
        List<Schedule> pastSchedules = scheduleMapper.selectByDateRange(today.minusDays(7), today.minusDays(1));
        int expired = 0;

        for (Schedule schedule : pastSchedules) {
            // Expire WAITING entries
            List<Waitlist> waitingList = waitlistMapper.selectByScheduleIdAndStatus(schedule.getId(), "WAITING");
            for (Waitlist w : waitingList) {
                waitlistMapper.updateStatus(w.getId(), "EXPIRED");
                auditUtil.log("WAITLIST", w.getId(), AuditAction.WAITLIST_EXPIRE, "SYSTEM",
                        "Expired: schedule date passed");
                expired++;
            }

            // Expire OFFERED entries
            List<Waitlist> offeredList = waitlistMapper.selectByScheduleIdAndStatus(schedule.getId(), "OFFERED");
            for (Waitlist w : offeredList) {
                waitlistMapper.updateStatus(w.getId(), "EXPIRED");
                auditUtil.log("WAITLIST", w.getId(), AuditAction.WAITLIST_EXPIRE, "SYSTEM",
                        "Expired: schedule date passed");
                expired++;
            }
        }

        log.info("Expired {} waitlist entries for past schedules", expired);
    }
}
