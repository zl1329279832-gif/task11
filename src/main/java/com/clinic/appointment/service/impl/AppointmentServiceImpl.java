package com.clinic.appointment.service.impl;

import com.clinic.appointment.domain.dto.BookRequest;
import com.clinic.appointment.domain.dto.CancelRequest;
import com.clinic.appointment.domain.dto.RescheduleRequest;
import com.clinic.appointment.domain.entity.Appointment;
import com.clinic.appointment.domain.entity.DoctorSchedule;
import com.clinic.appointment.domain.entity.ScheduleSlot;
import com.clinic.appointment.domain.enums.AppointmentStatus;
import com.clinic.appointment.domain.enums.SlotStatus;
import com.clinic.appointment.exception.BusinessException;
import com.clinic.appointment.mapper.AppointmentMapper;
import com.clinic.appointment.mapper.DoctorScheduleMapper;
import com.clinic.appointment.mapper.ScheduleSlotMapper;
import com.clinic.appointment.service.AppointmentService;
import com.clinic.appointment.service.AuditService;
import com.clinic.appointment.service.RedisLockService;
import com.clinic.appointment.service.WaitlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentServiceImpl implements AppointmentService {

    private final AppointmentMapper appointmentMapper;
    private final ScheduleSlotMapper slotMapper;
    private final DoctorScheduleMapper scheduleMapper;
    private final RedisLockService lockService;
    private final AuditService auditService;

    @Autowired
    @Lazy
    private WaitlistService waitlistService;

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Override
    @Transactional
    public Appointment book(BookRequest request) {
        // 1. 先查号源信息（无锁读）
        ScheduleSlot slot = slotMapper.selectById(request.getSlotId());
        if (slot == null || !SlotStatus.AVAILABLE.name().equals(slot.getStatus())) {
            throw BusinessException.slotNotAvailable();
        }

        // 2. 分布式锁保护（按号源粒度）
        String lockKey = "lock:slot:" + slot.getId();
        return lockService.executeWithLock(lockKey, () -> {
            // 3. 锁内重新检查号源状态
            ScheduleSlot freshSlot = slotMapper.selectById(request.getSlotId());
            if (freshSlot == null || !SlotStatus.AVAILABLE.name().equals(freshSlot.getStatus())) {
                throw BusinessException.slotNotAvailable();
            }

            // 4. 创建预约记录
            Appointment appointment = new Appointment();
            appointment.setAppointmentNo(generateNo());
            appointment.setPatientId(request.getPatientId());
            appointment.setPatientName(request.getPatientName() != null ? request.getPatientName() : "");
            appointment.setDoctorId(freshSlot.getDoctorId());
            appointment.setDepartmentId(freshSlot.getDepartmentId());
            appointment.setSlotId(freshSlot.getId());
            appointment.setSlotDate(freshSlot.getSlotDate());
            appointment.setSlotTime(freshSlot.getSlotTime());
            appointment.setStatus(AppointmentStatus.CONFIRMED.name());
            appointment.setSource("ONLINE");
            appointmentMapper.insert(appointment);

            // 5. CAS占用号源（乐观锁）
            int updated = slotMapper.casBook(freshSlot.getId(), appointment.getId(), freshSlot.getVersion());
            if (updated == 0) {
                throw BusinessException.slotNotAvailable();
            }

            // 6. 更新排班已预约数
            DoctorSchedule schedule = scheduleMapper.selectById(freshSlot.getScheduleId());
            if (schedule != null) {
                schedule.setBookedSlots(schedule.getBookedSlots() + 1);
                scheduleMapper.updateById(schedule);
            }

            // 7. 审计日志
            auditService.log("BOOK", "APPOINTMENT", appointment.getId(),
                    String.format("{\"patientId\":%d,\"slotId\":%d,\"date\":\"%s\",\"time\":\"%s\"}",
                            request.getPatientId(), freshSlot.getId(),
                            freshSlot.getSlotDate(), freshSlot.getSlotTime()));

            log.info("预约成功: no={}, patient={}, doctor={}, date={}, time={}",
                    appointment.getAppointmentNo(), request.getPatientId(),
                    freshSlot.getDoctorId(), freshSlot.getSlotDate(), freshSlot.getSlotTime());

            return appointment;
        });
    }

    @Override
    @Transactional
    public Appointment cancel(CancelRequest request) {
        Appointment appointment = appointmentMapper.selectById(request.getAppointmentId());
        if (appointment == null) {
            throw BusinessException.appointmentNotFound();
        }

        // 状态校验：只有PENDING和CONFIRMED可以取消
        String status = appointment.getStatus();
        if (!AppointmentStatus.PENDING.name().equals(status) &&
            !AppointmentStatus.CONFIRMED.name().equals(status)) {
            throw BusinessException.invalidStatusTransition(status, "CANCELLED");
        }

        // 分布式锁保护
        String lockKey = "lock:appointment:" + appointment.getId();
        return lockService.executeWithLock(lockKey, () -> {
            // 更新预约状态
            appointment.setStatus(AppointmentStatus.CANCELLED.name());
            appointment.setCancelReason(request.getReason() != null ? request.getReason() : "");
            appointmentMapper.updateById(appointment);

            // 释放号源
            ScheduleSlot slot = slotMapper.selectById(appointment.getSlotId());
            if (slot != null) {
                slotMapper.casRelease(slot.getId(), slot.getVersion());

                // 更新排班已预约数
                DoctorSchedule schedule = scheduleMapper.selectById(slot.getScheduleId());
                if (schedule != null && schedule.getBookedSlots() > 0) {
                    schedule.setBookedSlots(schedule.getBookedSlots() - 1);
                    scheduleMapper.updateById(schedule);
                }
            }

            auditService.log("CANCEL", "APPOINTMENT", appointment.getId(),
                    String.format("{\"reason\":\"%s\"}", request.getReason()));

            log.info("取消预约: no={}, reason={}", appointment.getAppointmentNo(), request.getReason());

            // 异步触发候补补位
            try {
                waitlistService.triggerBackfill(appointment.getDoctorId(),
                        appointment.getSlotDate(), appointment.getSlotTime());
            } catch (Exception e) {
                log.error("候补补位触发失败，将由定时任务补偿: {}", e.getMessage());
            }

            return appointment;
        });
    }

    @Override
    @Transactional
    public Appointment reschedule(RescheduleRequest request) {
        Appointment original = appointmentMapper.selectById(request.getAppointmentId());
        if (original == null) {
            throw BusinessException.appointmentNotFound();
        }

        String status = original.getStatus();
        if (!AppointmentStatus.PENDING.name().equals(status) &&
            !AppointmentStatus.CONFIRMED.name().equals(status)) {
            throw BusinessException.invalidStatusTransition(status, "RESCHEDULED");
        }

        // 新号源
        ScheduleSlot newSlot = slotMapper.selectById(request.getNewSlotId());
        if (newSlot == null || !SlotStatus.AVAILABLE.name().equals(newSlot.getStatus())) {
            throw BusinessException.slotNotAvailable();
        }

        // 锁保护：同时锁旧号源和新号源（按ID排序避免死锁）
        Long oldSlotId = original.getSlotId();
        Long newSlotId = newSlot.getId();
        String lockKey1 = "lock:slot:" + Math.min(oldSlotId, newSlotId);
        String lockKey2 = "lock:slot:" + Math.max(oldSlotId, newSlotId);

        return lockService.executeWithLock(lockKey1, () ->
            lockService.executeWithLock(lockKey2, () -> {
                // 重新校验新号源
                ScheduleSlot freshNew = slotMapper.selectById(newSlotId);
                if (freshNew == null || !SlotStatus.AVAILABLE.name().equals(freshNew.getStatus())) {
                    throw BusinessException.slotNotAvailable();
                }

                // 1. 释放旧号源
                ScheduleSlot oldSlot = slotMapper.selectById(oldSlotId);
                if (oldSlot != null) {
                    slotMapper.casRelease(oldSlotId, oldSlot.getVersion());
                }

                // 2. 标记旧预约为已改签
                original.setStatus(AppointmentStatus.RESCHEDULED.name());
                original.setCancelReason(request.getReason() != null ? request.getReason() : "改签");
                appointmentMapper.updateById(original);

                // 3. 创建新预约
                Appointment newAppointment = new Appointment();
                newAppointment.setAppointmentNo(generateNo());
                newAppointment.setPatientId(original.getPatientId());
                newAppointment.setPatientName(original.getPatientName());
                newAppointment.setDoctorId(freshNew.getDoctorId());
                newAppointment.setDepartmentId(freshNew.getDepartmentId());
                newAppointment.setSlotId(freshNew.getId());
                newAppointment.setSlotDate(freshNew.getSlotDate());
                newAppointment.setSlotTime(freshNew.getSlotTime());
                newAppointment.setStatus(AppointmentStatus.CONFIRMED.name());
                newAppointment.setSource(original.getSource());
                newAppointment.setOriginalId(original.getId());
                appointmentMapper.insert(newAppointment);

                // 4. CAS占用新号源
                int updated = slotMapper.casBook(freshNew.getId(), newAppointment.getId(), freshNew.getVersion());
                if (updated == 0) {
                    throw BusinessException.slotNotAvailable();
                }

                auditService.log("RESCHEDULE", "APPOINTMENT", newAppointment.getId(),
                        String.format("{\"fromId\":%d,\"fromSlot\":%d,\"toSlot\":%d}",
                                original.getId(), oldSlotId, newSlotId));

                log.info("改签成功: old={}, new={}, patient={}",
                        original.getAppointmentNo(), newAppointment.getAppointmentNo(),
                        original.getPatientId());

                // 旧号源释放后触发候补
                try {
                    waitlistService.triggerBackfill(original.getDoctorId(),
                            original.getSlotDate(), original.getSlotTime());
                } catch (Exception e) {
                    log.error("改签后候补补位触发失败: {}", e.getMessage());
                }

                return newAppointment;
            })
        );
    }

    @Override
    @Transactional
    public Appointment checkIn(Long appointmentId) {
        Appointment appointment = appointmentMapper.selectById(appointmentId);
        if (appointment == null) {
            throw BusinessException.appointmentNotFound();
        }
        if (!AppointmentStatus.CONFIRMED.name().equals(appointment.getStatus())) {
            throw BusinessException.invalidStatusTransition(appointment.getStatus(), "CHECKED_IN");
        }

        appointment.setStatus(AppointmentStatus.CHECKED_IN.name());
        appointmentMapper.updateById(appointment);

        // 同时更新号源状态
        ScheduleSlot slot = slotMapper.selectById(appointment.getSlotId());
        if (slot != null) {
            slot.setStatus(SlotStatus.CHECKED_IN.name());
            slotMapper.updateById(slot);
        }

        auditService.log("CHECK_IN", "APPOINTMENT", appointmentId, null);
        log.info("签到成功: appointmentNo={}", appointment.getAppointmentNo());

        return appointment;
    }

    @Override
    @Transactional
    public Appointment markMissed(Long appointmentId) {
        Appointment appointment = appointmentMapper.selectById(appointmentId);
        if (appointment == null) {
            throw BusinessException.appointmentNotFound();
        }
        if (!AppointmentStatus.CONFIRMED.name().equals(appointment.getStatus())) {
            throw BusinessException.invalidStatusTransition(appointment.getStatus(), "MISSED");
        }

        appointment.setStatus(AppointmentStatus.MISSED.name());
        appointmentMapper.updateById(appointment);

        ScheduleSlot slot = slotMapper.selectById(appointment.getSlotId());
        if (slot != null) {
            slot.setStatus(SlotStatus.MISSED.name());
            slotMapper.updateById(slot);
        }

        auditService.log("MISSED", "APPOINTMENT", appointmentId, null);
        log.info("过号: appointmentNo={}", appointment.getAppointmentNo());

        return appointment;
    }

    @Override
    public Appointment getById(Long id) {
        Appointment a = appointmentMapper.selectById(id);
        if (a == null) throw BusinessException.appointmentNotFound();
        return a;
    }

    @Override
    public List<Appointment> getByPatient(Long patientId) {
        return appointmentMapper.findActiveByPatient(patientId);
    }

    @Override
    public List<Appointment> getByDoctorAndDate(Long doctorId, LocalDate date) {
        return appointmentMapper.findByDoctorAndDate(doctorId, date);
    }

    @Override
    @Transactional
    public List<Appointment> batchCancel(Long doctorId, LocalDate startDate, LocalDate endDate, String reason) {
        List<Appointment> affected = appointmentMapper.findActiveByDoctorFromDate(doctorId, startDate);
        // 过滤日期范围
        List<Appointment> toCancel = affected.stream()
                .filter(a -> !a.getSlotDate().isBefore(startDate) && !a.getSlotDate().isAfter(endDate))
                .toList();

        for (Appointment appt : toCancel) {
            appt.setStatus(AppointmentStatus.CANCELLED.name());
            appt.setCancelReason(reason);
            appointmentMapper.updateById(appt);

            ScheduleSlot slot = slotMapper.selectById(appt.getSlotId());
            if (slot != null) {
                slotMapper.casRelease(slot.getId(), slot.getVersion());
            }
        }

        auditService.log("BATCH_CANCEL", "APPOINTMENT", null,
                String.format("{\"doctorId\":%d,\"start\":\"%s\",\"end\":\"%s\",\"count\":%d,\"reason\":\"%s\"}",
                        doctorId, startDate, endDate, toCancel.size(), reason));

        log.info("批量取消预约: doctor={}, range={}~{}, count={}", doctorId, startDate, endDate, toCancel.size());
        return toCancel;
    }

    private String generateNo() {
        return "APT" + LocalDateTime.now().format(NO_FMT) +
                UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
}
