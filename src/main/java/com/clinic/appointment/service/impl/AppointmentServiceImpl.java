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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 预约服务实现
 *
 * 并发安全设计：
 * - book/cancel/reschedule 均使用 Redisson 分布式锁（slot粒度）+ 编程式事务
 * - 事务在锁内提交，消除"锁释放-事务提交"窗口期导致的超卖/重复释放
 * - cancel 锁内重新校验预约状态，防止并发 cancel 重复释放号源和触发候补
 * - batchCancel 逐条检查当前状态，跳过已被并发修改的预约
 */
@Slf4j
@Service
public class AppointmentServiceImpl implements AppointmentService {

    private final AppointmentMapper appointmentMapper;
    private final ScheduleSlotMapper slotMapper;
    private final DoctorScheduleMapper scheduleMapper;
    private final RedisLockService lockService;
    private final AuditService auditService;
    private final TransactionTemplate txTemplate;

    @Autowired
    @Lazy
    private WaitlistService waitlistService;

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public AppointmentServiceImpl(AppointmentMapper appointmentMapper,
                                  ScheduleSlotMapper slotMapper,
                                  DoctorScheduleMapper scheduleMapper,
                                  RedisLockService lockService,
                                  AuditService auditService,
                                  PlatformTransactionManager txManager) {
        this.appointmentMapper = appointmentMapper;
        this.slotMapper = slotMapper;
        this.scheduleMapper = scheduleMapper;
        this.lockService = lockService;
        this.auditService = auditService;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * 预约挂号
     *
     * 锁粒度：lock:slot:{slotId}
     * 事务：在锁内通过 TransactionTemplate 提交，确保锁释放前数据已落盘
     * 防超卖：Redis锁互斥 + DB乐观锁CAS双重保障
     */
    @Override
    public Appointment book(BookRequest request) {
        // 1. 无锁快速失败
        ScheduleSlot slot = slotMapper.selectById(request.getSlotId());
        if (slot == null || !SlotStatus.AVAILABLE.name().equals(slot.getStatus())) {
            throw BusinessException.slotNotAvailable();
        }

        // 2. 分布式锁保护（按号源粒度）
        String lockKey = "lock:slot:" + slot.getId();
        return lockService.executeWithLock(lockKey, () ->
            // 3. 事务在锁内提交
            txTemplate.execute(status -> {
                // 4. 锁内重新检查号源状态
                ScheduleSlot freshSlot = slotMapper.selectById(request.getSlotId());
                if (freshSlot == null || !SlotStatus.AVAILABLE.name().equals(freshSlot.getStatus())) {
                    throw BusinessException.slotNotAvailable();
                }

                // 5. 检查排班状态，防止停诊后的号源被预约
                DoctorSchedule schedule = scheduleMapper.selectById(freshSlot.getScheduleId());
                if (schedule == null || !"NORMAL".equals(schedule.getStatus())) {
                    throw BusinessException.slotNotAvailable();
                }

                // 6. 创建预约记录
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

                // 7. CAS占用号源（乐观锁）
                int updated = slotMapper.casBook(freshSlot.getId(), appointment.getId(), freshSlot.getVersion());
                if (updated == 0) {
                    throw BusinessException.slotNotAvailable();
                }

                // 8. 更新排班已预约数
                schedule.setBookedSlots(schedule.getBookedSlots() + 1);
                scheduleMapper.updateById(schedule);

                auditService.log("BOOK", "APPOINTMENT", appointment.getId(),
                        String.format("{\"patientId\":%d,\"slotId\":%d,\"date\":\"%s\",\"time\":\"%s\"}",
                                request.getPatientId(), freshSlot.getId(),
                                freshSlot.getSlotDate(), freshSlot.getSlotTime()));

                log.info("预约成功: no={}, patient={}, doctor={}, date={}, time={}",
                        appointment.getAppointmentNo(), request.getPatientId(),
                        freshSlot.getDoctorId(), freshSlot.getSlotDate(), freshSlot.getSlotTime());

                return appointment;
            })
        );
    }

    /**
     * 取消预约
     *
     * 锁粒度：lock:slot:{slotId}（与book()互斥，防止cancel释放号源后、事务提交前被book抢占）
     * 锁内重新校验预约状态，防止并发cancel重复释放号源和触发候补
     * 候补补位在事务提交后、锁释放前触发，确保释放的号源对候补可见
     */
    @Override
    public Appointment cancel(CancelRequest request) {
        // 快速失败
        Appointment appointment = appointmentMapper.selectById(request.getAppointmentId());
        if (appointment == null) {
            throw BusinessException.appointmentNotFound();
        }

        // 使用号源锁（与book互斥）
        String lockKey = "lock:slot:" + appointment.getSlotId();
        return lockService.executeWithLock(lockKey, () -> {
            // 事务在锁内提交
            Appointment result = txTemplate.execute(status -> {
                // 锁内重新查询，防止并发cancel
                Appointment freshAppt = appointmentMapper.selectById(request.getAppointmentId());
                if (freshAppt == null) {
                    throw BusinessException.appointmentNotFound();
                }

                // 状态校验：只有PENDING和CONFIRMED可以取消
                String currentStatus = freshAppt.getStatus();
                if (!AppointmentStatus.PENDING.name().equals(currentStatus) &&
                    !AppointmentStatus.CONFIRMED.name().equals(currentStatus)) {
                    throw BusinessException.invalidStatusTransition(currentStatus, "CANCELLED");
                }

                // 更新预约状态
                freshAppt.setStatus(AppointmentStatus.CANCELLED.name());
                freshAppt.setCancelReason(request.getReason() != null ? request.getReason() : "");
                appointmentMapper.updateById(freshAppt);

                // 释放号源（仅当号源当前为BOOKED时，防止重复释放）
                ScheduleSlot slot = slotMapper.selectById(freshAppt.getSlotId());
                if (slot != null && SlotStatus.BOOKED.name().equals(slot.getStatus())) {
                    int released = slotMapper.casRelease(slot.getId(), slot.getVersion());
                    if (released > 0) {
                        DoctorSchedule schedule = scheduleMapper.selectById(slot.getScheduleId());
                        if (schedule != null && schedule.getBookedSlots() > 0) {
                            schedule.setBookedSlots(schedule.getBookedSlots() - 1);
                            scheduleMapper.updateById(schedule);
                        }
                    }
                }

                auditService.log("CANCEL", "APPOINTMENT", freshAppt.getId(),
                        String.format("{\"reason\":\"%s\"}", request.getReason()));

                log.info("取消预约: no={}, reason={}", freshAppt.getAppointmentNo(), request.getReason());
                return freshAppt;
            });

            // 事务已提交，在锁保护下触发候补补位（释放的号源已对候补可见）
            try {
                waitlistService.triggerBackfill(result.getDoctorId(),
                        result.getSlotDate(), result.getSlotTime());
            } catch (Exception e) {
                log.error("候补补位触发失败，将由定时任务补偿: {}", e.getMessage());
            }

            return result;
        });
    }

    /**
     * 改签预约
     *
     * 锁粒度：同时锁旧号源和新号源（按ID排序避免死锁）
     * 事务在锁内提交，候补触发在事务提交后
     */
    @Override
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

        ScheduleSlot newSlot = slotMapper.selectById(request.getNewSlotId());
        if (newSlot == null || !SlotStatus.AVAILABLE.name().equals(newSlot.getStatus())) {
            throw BusinessException.slotNotAvailable();
        }

        Long oldSlotId = original.getSlotId();
        Long newSlotId = newSlot.getId();
        String lockKey1 = "lock:slot:" + Math.min(oldSlotId, newSlotId);
        String lockKey2 = "lock:slot:" + Math.max(oldSlotId, newSlotId);

        return lockService.executeWithLock(lockKey1, () ->
            lockService.executeWithLock(lockKey2, () -> {
                Appointment result = txTemplate.execute(txStatus -> {
                    // 锁内重新校验原预约
                    Appointment freshOriginal = appointmentMapper.selectById(request.getAppointmentId());
                    if (freshOriginal == null) {
                        throw BusinessException.appointmentNotFound();
                    }
                    String origStatus = freshOriginal.getStatus();
                    if (!AppointmentStatus.PENDING.name().equals(origStatus) &&
                        !AppointmentStatus.CONFIRMED.name().equals(origStatus)) {
                        throw BusinessException.invalidStatusTransition(origStatus, "RESCHEDULED");
                    }

                    // 锁内重新校验新号源
                    ScheduleSlot freshNew = slotMapper.selectById(newSlotId);
                    if (freshNew == null || !SlotStatus.AVAILABLE.name().equals(freshNew.getStatus())) {
                        throw BusinessException.slotNotAvailable();
                    }

                    // 1. 释放旧号源
                    ScheduleSlot oldSlot = slotMapper.selectById(oldSlotId);
                    if (oldSlot != null && SlotStatus.BOOKED.name().equals(oldSlot.getStatus())) {
                        slotMapper.casRelease(oldSlotId, oldSlot.getVersion());
                    }

                    // 2. 标记旧预约为已改签
                    freshOriginal.setStatus(AppointmentStatus.RESCHEDULED.name());
                    freshOriginal.setCancelReason(request.getReason() != null ? request.getReason() : "改签");
                    appointmentMapper.updateById(freshOriginal);

                    // 3. 创建新预约
                    Appointment newAppointment = new Appointment();
                    newAppointment.setAppointmentNo(generateNo());
                    newAppointment.setPatientId(freshOriginal.getPatientId());
                    newAppointment.setPatientName(freshOriginal.getPatientName());
                    newAppointment.setDoctorId(freshNew.getDoctorId());
                    newAppointment.setDepartmentId(freshNew.getDepartmentId());
                    newAppointment.setSlotId(freshNew.getId());
                    newAppointment.setSlotDate(freshNew.getSlotDate());
                    newAppointment.setSlotTime(freshNew.getSlotTime());
                    newAppointment.setStatus(AppointmentStatus.CONFIRMED.name());
                    newAppointment.setSource(freshOriginal.getSource());
                    newAppointment.setOriginalId(freshOriginal.getId());
                    appointmentMapper.insert(newAppointment);

                    // 4. CAS占用新号源
                    int updated = slotMapper.casBook(freshNew.getId(), newAppointment.getId(), freshNew.getVersion());
                    if (updated == 0) {
                        throw BusinessException.slotNotAvailable();
                    }

                    auditService.log("RESCHEDULE", "APPOINTMENT", newAppointment.getId(),
                            String.format("{\"fromId\":%d,\"fromSlot\":%d,\"toSlot\":%d}",
                                    freshOriginal.getId(), oldSlotId, newSlotId));

                    log.info("改签成功: old={}, new={}, patient={}",
                            freshOriginal.getAppointmentNo(), newAppointment.getAppointmentNo(),
                            freshOriginal.getPatientId());

                    return newAppointment;
                });

                // 事务已提交，触发旧号源的候补补位
                try {
                    waitlistService.triggerBackfill(original.getDoctorId(),
                            original.getSlotDate(), original.getSlotTime());
                } catch (Exception e) {
                    log.error("改签后候补补位触发失败: {}", e.getMessage());
                }

                return result;
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

    /**
     * 批量取消预约
     *
     * 逐条检查当前状态，跳过已被并发修改的预约，防止重复释放号源。
     * 注意：停诊场景调用此方法时，号源最终由 suspendSlots 统一冻结为 SUSPENDED。
     */
    @Override
    public List<Appointment> batchCancel(Long doctorId, LocalDate startDate, LocalDate endDate, String reason) {
        return txTemplate.execute(status -> {
            List<Appointment> affected = appointmentMapper.findActiveByDoctorFromDate(doctorId, startDate);
            List<Appointment> toCancel = affected.stream()
                    .filter(a -> !a.getSlotDate().isBefore(startDate) && !a.getSlotDate().isAfter(endDate))
                    .toList();

            List<Appointment> cancelled = new ArrayList<>();
            for (Appointment appt : toCancel) {
                // 重新查询，防止并发修改
                Appointment fresh = appointmentMapper.selectById(appt.getId());
                if (fresh == null) continue;
                String apptStatus = fresh.getStatus();
                if (!AppointmentStatus.PENDING.name().equals(apptStatus) &&
                    !AppointmentStatus.CONFIRMED.name().equals(apptStatus)) {
                    continue;
                }

                fresh.setStatus(AppointmentStatus.CANCELLED.name());
                fresh.setCancelReason(reason);
                appointmentMapper.updateById(fresh);

                // 释放号源（仅当号源当前为BOOKED时）
                ScheduleSlot slot = slotMapper.selectById(fresh.getSlotId());
                if (slot != null && SlotStatus.BOOKED.name().equals(slot.getStatus())) {
                    slotMapper.casRelease(slot.getId(), slot.getVersion());
                }

                cancelled.add(fresh);
            }

            auditService.log("BATCH_CANCEL", "APPOINTMENT", null,
                    String.format("{\"doctorId\":%d,\"start\":\"%s\",\"end\":\"%s\",\"count\":%d,\"reason\":\"%s\"}",
                            doctorId, startDate, endDate, cancelled.size(), reason));

            log.info("批量取消预约: doctor={}, range={}~{}, count={}", doctorId, startDate, endDate, cancelled.size());
            return cancelled;
        });
    }

    private String generateNo() {
        return "APT" + LocalDateTime.now().format(NO_FMT) +
                UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
}
