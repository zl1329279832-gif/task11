package com.clinic.appointment.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.clinic.appointment.domain.entity.Appointment;
import com.clinic.appointment.domain.entity.ScheduleSlot;
import com.clinic.appointment.domain.enums.AppointmentStatus;
import com.clinic.appointment.domain.enums.SlotStatus;
import com.clinic.appointment.mapper.AppointmentMapper;
import com.clinic.appointment.mapper.ScheduleSlotMapper;
import com.clinic.appointment.service.WaitlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 定时任务
 *
 * 并发安全设计：
 * - expireSlots 只处理 AVAILABLE 状态的号源，已停诊(SUSPENDED)号源不受影响
 * - markMissedAppointments 只处理 CONFIRMED 状态的预约
 * - waitlistBackfillCompensation 触发的 triggerBackfill 内部使用
 *   findAvailableWithScheduleCheck 排除已停诊排班的号源，
 *   不会对已停诊的号源触发补位
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTasks {

    private final ScheduleSlotMapper slotMapper;
    private final AppointmentMapper appointmentMapper;
    private final WaitlistService waitlistService;

    /**
     * 每小时：清理过期号源（当天已过时间的AVAILABLE号源标记为EXPIRED）
     * 注意：SUSPENDED 状态的号源不会被处理（只查 AVAILABLE）
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void expireSlots() {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();

        LambdaQueryWrapper<ScheduleSlot> qw = new LambdaQueryWrapper<>();
        qw.eq(ScheduleSlot::getSlotDate, today)
          .lt(ScheduleSlot::getSlotTime, now)
          .eq(ScheduleSlot::getStatus, SlotStatus.AVAILABLE.name());
        List<ScheduleSlot> expired = slotMapper.selectList(qw);

        int count = 0;
        for (ScheduleSlot slot : expired) {
            slot.setStatus(SlotStatus.EXPIRED.name());
            slotMapper.updateById(slot);
            count++;
        }

        if (count > 0) {
            log.info("过期号源清理: date={}, count={}", today, count);
        }
    }

    /**
     * 每30分钟：检查未签到预约，超过预约时间30分钟未签到自动过号
     */
    @Scheduled(cron = "0 */30 * * * ?")
    public void markMissedAppointments() {
        LocalDate today = LocalDate.now();
        LocalTime cutoff = LocalTime.now().minusMinutes(30);

        LambdaQueryWrapper<Appointment> qw = new LambdaQueryWrapper<>();
        qw.eq(Appointment::getSlotDate, today)
          .lt(Appointment::getSlotTime, cutoff)
          .eq(Appointment::getStatus, AppointmentStatus.CONFIRMED.name());
        List<Appointment> missed = appointmentMapper.selectList(qw);

        int count = 0;
        for (Appointment appt : missed) {
            appt.setStatus(AppointmentStatus.MISSED.name());
            appointmentMapper.updateById(appt);

            ScheduleSlot slot = slotMapper.selectById(appt.getSlotId());
            if (slot != null && SlotStatus.BOOKED.name().equals(slot.getStatus())) {
                slot.setStatus(SlotStatus.MISSED.name());
                slotMapper.updateById(slot);
            }
            count++;
        }

        if (count > 0) {
            log.info("自动过号: date={}, count={}", today, count);
        }
    }

    /**
     * 每天凌晨1点：清理过期候补
     */
    @Scheduled(cron = "0 0 1 * * ?")
    public void expireWaitlists() {
        int count = waitlistService.expireWaitlists();
        if (count > 0) {
            log.info("候补过期清理: count={}", count);
        }
    }

    /**
     * 每15分钟：候补补位补偿任务
     *
     * 扫描有可用号源且有候补队列的情况，触发补位。
     * triggerBackfill 内部使用 findAvailableWithScheduleCheck 排除已停诊排班，
     * 不会对已停诊的号源触发补位。
     */
    @Scheduled(cron = "0 */15 * * * ?")
    public void waitlistBackfillCompensation() {
        LocalDate today = LocalDate.now();
        LocalDate endDate = today.plusDays(7);

        // 查找未来7天内 AVAILABLE 的号源（SUSPENDED号源不在此列）
        LambdaQueryWrapper<ScheduleSlot> qw = new LambdaQueryWrapper<>();
        qw.eq(ScheduleSlot::getStatus, SlotStatus.AVAILABLE.name())
          .ge(ScheduleSlot::getSlotDate, today)
          .le(ScheduleSlot::getSlotDate, endDate)
          .orderByAsc(ScheduleSlot::getSlotDate);
        List<ScheduleSlot> availableSlots = slotMapper.selectList(qw);

        // 按医生+日期去重
        availableSlots.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        s -> s.getDoctorId() + ":" + s.getSlotDate()))
                .forEach((key, slots) -> {
                    Long doctorId = slots.get(0).getDoctorId();
                    LocalDate date = slots.get(0).getSlotDate();
                    try {
                        waitlistService.triggerBackfill(doctorId, date, null);
                    } catch (Exception e) {
                        log.error("候补补偿任务失败: doctor={}, date={}, error={}",
                                doctorId, date, e.getMessage());
                    }
                });
    }
}
