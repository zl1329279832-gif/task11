package com.clinic.appointment.service.impl;

import com.clinic.appointment.exception.BusinessException;
import com.clinic.appointment.mapper.ScheduleMapper;
import com.clinic.appointment.mapper.ScheduleTemplateMapper;
import com.clinic.appointment.mapper.SlotMapper;
import com.clinic.appointment.model.entity.Schedule;
import com.clinic.appointment.model.entity.ScheduleTemplate;
import com.clinic.appointment.model.entity.Slot;
import com.clinic.appointment.model.enums.AuditAction;
import com.clinic.appointment.model.enums.ScheduleStatus;
import com.clinic.appointment.model.enums.SlotStatus;
import com.clinic.appointment.service.HolidayService;
import com.clinic.appointment.service.ScheduleService;
import com.clinic.appointment.util.AuditUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleServiceImpl implements ScheduleService {

    private final ScheduleMapper scheduleMapper;
    private final ScheduleTemplateMapper scheduleTemplateMapper;
    private final SlotMapper slotMapper;
    private final HolidayService holidayService;
    private final AuditUtil auditUtil;

    @Override
    @Transactional
    public int generateSchedules(LocalDate startDate, LocalDate endDate) {
        int count = 0;
        LocalDate date = startDate;

        while (!date.isAfter(endDate)) {
            if (holidayService.isHoliday(date)) {
                date = date.plusDays(1);
                continue;
            }

            int dayOfWeek = date.getDayOfWeek().getValue(); // 1=Mon, 7=Sun
            List<ScheduleTemplate> templates = scheduleTemplateMapper.selectEnabledByDayOfWeek(dayOfWeek);

            for (ScheduleTemplate template : templates) {
                // Check if schedule already exists for this doctor+date+period
                Schedule existing = scheduleMapper.selectActiveByDoctorAndDate(
                        template.getDoctorId(), date, template.getPeriod());
                if (existing != null) {
                    continue;
                }

                // Create Schedule entity from template
                Schedule schedule = Schedule.builder()
                        .doctorId(template.getDoctorId())
                        .scheduleDate(date)
                        .period(template.getPeriod())
                        .startTime(template.getStartTime())
                        .endTime(template.getEndTime())
                        .totalSlots(template.getMaxSlots())
                        .bookedCount(0)
                        .extraSlots(0)
                        .status(ScheduleStatus.ACTIVE.name())
                        .templateId(template.getId())
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
                scheduleMapper.insert(schedule);

                // Generate individual Slot entities
                long slotDuration = calculateSlotDurationMinutes(
                        template.getStartTime(), template.getEndTime(), template.getMaxSlots());
                List<Slot> slots = new ArrayList<>();
                for (int i = 1; i <= template.getMaxSlots(); i++) {
                    LocalTime slotStart = template.getStartTime().plusMinutes(slotDuration * (i - 1));
                    LocalTime slotEnd = slotStart.plusMinutes(slotDuration);
                    Slot slot = Slot.builder()
                            .scheduleId(schedule.getId())
                            .doctorId(template.getDoctorId())
                            .scheduleDate(date)
                            .period(template.getPeriod())
                            .seqNum(i)
                            .startTime(slotStart)
                            .endTime(slotEnd)
                            .status(SlotStatus.AVAILABLE.name())
                            .isExtra(false)
                            .version(0)
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();
                    slots.add(slot);
                }

                if (!slots.isEmpty()) {
                    slotMapper.batchInsert(slots);
                }

                count++;
                log.info("Generated schedule for doctor={} date={} period={}",
                        template.getDoctorId(), date, template.getPeriod());
            }

            date = date.plusDays(1);
        }

        log.info("Generated {} schedules from {} to {}", count, startDate, endDate);
        return count;
    }

    @Override
    public Schedule getById(Long id) {
        return scheduleMapper.selectById(id);
    }

    @Override
    public List<Schedule> getByDoctorAndDate(Long doctorId, LocalDate date) {
        return scheduleMapper.selectByDoctorAndDateRange(doctorId, date, date);
    }

    @Override
    public List<Schedule> getByDateRange(LocalDate start, LocalDate end) {
        return scheduleMapper.selectByDateRange(start, end);
    }

    @Override
    public List<Schedule> getByDoctorAndDateRange(Long doctorId, LocalDate start, LocalDate end) {
        return scheduleMapper.selectByDoctorAndDateRange(doctorId, start, end);
    }

    @Override
    @Transactional
    public void addExtraSlots(Long scheduleId, int count) {
        Schedule schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null) {
            throw new BusinessException("Schedule not found: " + scheduleId);
        }
        if (!ScheduleStatus.ACTIVE.name().equals(schedule.getStatus())) {
            throw new BusinessException("Schedule is not ACTIVE, cannot add extra slots");
        }

        int currentTotal = schedule.getTotalSlots();
        int currentExtra = schedule.getExtraSlots();

        List<Slot> extraSlotList = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Slot slot = Slot.builder()
                    .scheduleId(scheduleId)
                    .doctorId(schedule.getDoctorId())
                    .scheduleDate(schedule.getScheduleDate())
                    .period(schedule.getPeriod())
                    .seqNum(currentTotal + i)
                    .startTime(schedule.getEndTime())
                    .endTime(schedule.getEndTime())
                    .status(SlotStatus.AVAILABLE.name())
                    .isExtra(true)
                    .version(0)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            extraSlotList.add(slot);
        }

        if (!extraSlotList.isEmpty()) {
            slotMapper.batchInsert(extraSlotList);
        }

        // Update schedule: extraSlots += count, totalSlots += count
        schedule.setExtraSlots(currentExtra + count);
        schedule.setTotalSlots(currentTotal + count);
        schedule.setUpdatedAt(LocalDateTime.now());
        scheduleMapper.update(schedule);

        auditUtil.log("SCHEDULE", scheduleId, AuditAction.EXTRA_SLOT,
                "SYSTEM", "Added " + count + " extra slots to schedule " + scheduleId);

        log.info("Added {} extra slots to schedule {}", count, scheduleId);
    }

    @Override
    @Transactional
    public void suspend(Long scheduleId) {
        Schedule schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null) {
            throw new BusinessException("Schedule not found: " + scheduleId);
        }

        scheduleMapper.updateStatus(scheduleId, ScheduleStatus.SUSPENDED.name());

        auditUtil.log("SCHEDULE", scheduleId, AuditAction.SUSPEND,
                "SYSTEM", "Suspended schedule " + scheduleId);

        log.info("Suspended schedule {}", scheduleId);
    }

    private long calculateSlotDurationMinutes(LocalTime start, LocalTime end, int maxSlots) {
        long totalMinutes = Duration.between(start, end).toMinutes();
        return totalMinutes / maxSlots;
    }
}
