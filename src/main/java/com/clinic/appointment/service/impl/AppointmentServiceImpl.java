package com.clinic.appointment.service.impl;

import com.clinic.appointment.domain.dto.BookRequest;
import com.clinic.appointment.domain.dto.CancelRequest;
import com.clinic.appointment.domain.dto.RescheduleRequest;
import com.clinic.appointment.domain.dto.ResourceAllocation;
import com.clinic.appointment.domain.entity.Appointment;
import com.clinic.appointment.domain.entity.AppointmentResource;
import com.clinic.appointment.domain.entity.DoctorSchedule;
import com.clinic.appointment.domain.entity.ExamType;
import com.clinic.appointment.domain.entity.ScheduleSlot;
import com.clinic.appointment.domain.enums.AppointmentStatus;
import com.clinic.appointment.domain.enums.SlotStatus;
import com.clinic.appointment.exception.BusinessException;
import com.clinic.appointment.mapper.AppointmentMapper;
import com.clinic.appointment.mapper.AppointmentResourceMapper;
import com.clinic.appointment.mapper.DoctorScheduleMapper;
import com.clinic.appointment.mapper.ExamTypeMapper;
import com.clinic.appointment.mapper.ScheduleSlotMapper;
import com.clinic.appointment.service.AppointmentService;
import com.clinic.appointment.service.AuditService;
import com.clinic.appointment.service.RedisLockService;
import com.clinic.appointment.service.ResourceScheduleService;
import com.clinic.appointment.service.WaitlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentServiceImpl implements AppointmentService {

    private final AppointmentMapper appointmentMapper;
    private final ScheduleSlotMapper slotMapper;
    private final DoctorScheduleMapper scheduleMapper;
    private final RedisLockService lockService;
    private final AuditService auditService;
    private final ExamTypeMapper examTypeMapper;
    private final AppointmentResourceMapper appointmentResourceMapper;

    @Autowired
    @Lazy
    private WaitlistService waitlistService;

    @Autowired
    @Lazy
    private ResourceScheduleService resourceScheduleService;

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Override
    @Transactional
    public Appointment book(BookRequest request) {
        // 1. 先查号源信息（无锁读）
        ScheduleSlot slot = slotMapper.selectById(request.getSlotId());
        if (slot == null || !SlotStatus.AVAILABLE.name().equals(slot.getStatus())) {
            throw BusinessException.slotNotAvailable();
        }

        // 2. 判断是否联合预约
        boolean isJoint = request.getExamTypeCode() != null;
        ExamType examType = null;
        ResourceAllocation allocation = null;

        if (isJoint) {
            examType = examTypeMapper.findByCode(request.getExamTypeCode());
            if (examType == null) {
                throw BusinessException.examTypeNotFound();
            }
            // 校验患者每日检查次数限制
            resourceScheduleService.validatePatientLimits(
                    request.getPatientId(), request.getExamTypeCode(), slot.getSlotDate());
            // 解析所需资源（只读，锁外）
            allocation = resourceScheduleService.resolveResources(
                    examType, slot.getSlotDate(), slot.getSlotTime(), request);
        }

        // 3. 构建有序锁键列表（防死锁）
        List<String> lockKeys = buildOrderedLockKeys(slot.getId(), allocation);

        // 4. 递归加锁后执行预约
        final ResourceAllocation finalAllocation = allocation;
        return executeWithOrderedLocks(lockKeys, 0, () -> {
            // 5. 锁内重新检查号源状态
            ScheduleSlot freshSlot = slotMapper.selectById(request.getSlotId());
            if (freshSlot == null || !SlotStatus.AVAILABLE.name().equals(freshSlot.getStatus())) {
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
            appointment.setBookingType(isJoint ? "JOINT" : "SINGLE");
            appointment.setExamTypeCode(isJoint ? request.getExamTypeCode() : null);
            appointmentMapper.insert(appointment);

            // 7. CAS占用号源（乐观锁）
            int updated = slotMapper.casBook(freshSlot.getId(), appointment.getId(), freshSlot.getVersion());
            if (updated == 0) {
                throw BusinessException.slotNotAvailable();
            }

            // 8. 联合预约：CAS预订所有资源（任一失败抛异常→事务回滚）
            if (isJoint && finalAllocation != null && !finalAllocation.isEmpty()) {
                resourceScheduleService.bookResources(appointment.getId(), finalAllocation);
            }

            // 9. 更新排班已预约数
            DoctorSchedule schedule = scheduleMapper.selectById(freshSlot.getScheduleId());
            if (schedule != null) {
                schedule.setBookedSlots(schedule.getBookedSlots() + 1);
                scheduleMapper.updateById(schedule);
            }

            // 10. 审计日志
            auditService.log("BOOK", "APPOINTMENT", appointment.getId(),
                    String.format("{\"patientId\":%d,\"slotId\":%d,\"date\":\"%s\",\"time\":\"%s\",\"bookingType\":\"%s\",\"examType\":\"%s\"}",
                            request.getPatientId(), freshSlot.getId(),
                            freshSlot.getSlotDate(), freshSlot.getSlotTime(),
                            appointment.getBookingType(),
                            appointment.getExamTypeCode()));

            log.info("预约成功: no={}, patient={}, doctor={}, date={}, time={}, type={}",
                    appointment.getAppointmentNo(), request.getPatientId(),
                    freshSlot.getDoctorId(), freshSlot.getSlotDate(), freshSlot.getSlotTime(),
                    appointment.getBookingType());

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

        // 构建锁键列表：slot锁 + 联合预约的资源锁
        List<String> lockKeys = buildCancelLockKeys(appointment);

        return executeWithOrderedLocks(lockKeys, 0, () -> {
            // 原子CAS更新预约状态（防止并发取消）
            String reason = request.getReason() != null ? request.getReason() : "";
            int cancelUpdated = appointmentMapper.casCancelById(request.getAppointmentId(), reason);
            if (cancelUpdated == 0) {
                throw BusinessException.invalidStatusTransition("CANCELLED", "CANCELLED");
            }

            // 重新读取已取消的预约
            Appointment freshAppt = appointmentMapper.selectById(request.getAppointmentId());

            // 重新读取号源（锁内）
            ScheduleSlot slot = slotMapper.selectById(freshAppt.getSlotId());

            // 释放号源并检查返回值
            if (slot != null && SlotStatus.BOOKED.name().equals(slot.getStatus())) {
                int released = slotMapper.casRelease(slot.getId(), slot.getVersion());
                if (released == 0) {
                    throw BusinessException.of("SLOT_RELEASE_FAILED",
                            "号源释放失败（已被其他操作修改），请重试");
                }

                // 更新排班已预约数
                DoctorSchedule schedule = scheduleMapper.selectById(slot.getScheduleId());
                if (schedule != null && schedule.getBookedSlots() > 0) {
                    schedule.setBookedSlots(schedule.getBookedSlots() - 1);
                    scheduleMapper.updateById(schedule);
                }
            }

            // 联合预约：释放所有资源
            if ("JOINT".equals(freshAppt.getBookingType())) {
                resourceScheduleService.releaseResources(freshAppt.getId());
            }

            auditService.log("CANCEL", "APPOINTMENT", freshAppt.getId(),
                    String.format("{\"reason\":\"%s\"}", request.getReason()));

            log.info("取消预约: no={}, reason={}", freshAppt.getAppointmentNo(), request.getReason());

            // 事务提交后再触发候补补位
            final Long doctorId = freshAppt.getDoctorId();
            final LocalDate slotDate = freshAppt.getSlotDate();
            final java.time.LocalTime slotTime = freshAppt.getSlotTime();
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            waitlistService.triggerBackfill(doctorId, slotDate, slotTime);
                        } catch (Exception e) {
                            log.error("候补补位触发失败，将由定时任务补偿: {}", e.getMessage());
                        }
                    }
                });
            }

            return freshAppt;
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

        // 判断是否联合预约改签
        boolean isJoint = "JOINT".equals(original.getBookingType());
        ResourceAllocation newAllocation = null;

        if (isJoint) {
            ExamType examType = examTypeMapper.findByCode(original.getExamTypeCode());
            if (examType == null) {
                throw BusinessException.examTypeNotFound();
            }
            // 构建改签用的BookRequest以传递资源指定
            BookRequest resReq = new BookRequest();
            resReq.setRoomResourceSlotId(request.getNewRoomResourceSlotId());
            resReq.setEquipmentResourceSlotId(request.getNewEquipmentResourceSlotId());
            resReq.setNursingResourceSlotId(request.getNewNursingResourceSlotId());
            newAllocation = resourceScheduleService.resolveResources(
                    examType, newSlot.getSlotDate(), newSlot.getSlotTime(), resReq);
        }

        // 构建锁键列表：旧slot + 新slot + 旧资源 + 新资源，排序防死锁
        List<String> lockKeys = buildRescheduleLockKeys(
                original.getSlotId(), newSlot.getId(), original, newAllocation);

        final ResourceAllocation finalNewAllocation = newAllocation;
        return executeWithOrderedLocks(lockKeys, 0, () -> {
            // 重新校验新号源
            ScheduleSlot freshNew = slotMapper.selectById(request.getNewSlotId());
            if (freshNew == null || !SlotStatus.AVAILABLE.name().equals(freshNew.getStatus())) {
                throw BusinessException.slotNotAvailable();
            }

            // 1. 释放旧资源（联合预约）
            if (isJoint) {
                resourceScheduleService.releaseResources(original.getId());
            }

            // 2. 释放旧号源
            Long oldSlotId = original.getSlotId();
            ScheduleSlot oldSlot = slotMapper.selectById(oldSlotId);
            if (oldSlot != null) {
                slotMapper.casRelease(oldSlotId, oldSlot.getVersion());
            }

            // 3. 标记旧预约为已改签
            original.setStatus(AppointmentStatus.RESCHEDULED.name());
            original.setCancelReason(request.getReason() != null ? request.getReason() : "改签");
            appointmentMapper.updateById(original);

            // 4. 创建新预约
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
            newAppointment.setBookingType(original.getBookingType());
            newAppointment.setExamTypeCode(original.getExamTypeCode());
            appointmentMapper.insert(newAppointment);

            // 5. CAS占用新号源
            int updated = slotMapper.casBook(freshNew.getId(), newAppointment.getId(), freshNew.getVersion());
            if (updated == 0) {
                throw BusinessException.slotNotAvailable();
            }

            // 6. 联合预约：CAS预订新资源
            if (isJoint && finalNewAllocation != null && !finalNewAllocation.isEmpty()) {
                resourceScheduleService.bookResources(newAppointment.getId(), finalNewAllocation);
            }

            auditService.log("RESCHEDULE", "APPOINTMENT", newAppointment.getId(),
                    String.format("{\"fromId\":%d,\"fromSlot\":%d,\"toSlot\":%d}",
                            original.getId(), oldSlotId, request.getNewSlotId()));

            log.info("改签成功: old={}, new={}, patient={}, type={}",
                    original.getAppointmentNo(), newAppointment.getAppointmentNo(),
                    original.getPatientId(), original.getBookingType());

            // 事务提交后触发旧号源的候补补位
            final Long oldDoctorId = original.getDoctorId();
            final LocalDate oldDate = original.getSlotDate();
            final java.time.LocalTime oldTime = original.getSlotTime();
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            waitlistService.triggerBackfill(oldDoctorId, oldDate, oldTime);
                        } catch (Exception e) {
                            log.error("改签后候补补位触发失败: {}", e.getMessage());
                        }
                    }
                });
            }

            return newAppointment;
        });
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

            // 联合预约：释放资源
            if ("JOINT".equals(appt.getBookingType())) {
                resourceScheduleService.releaseResources(appt.getId());
            }
        }

        auditService.log("BATCH_CANCEL", "APPOINTMENT", null,
                String.format("{\"doctorId\":%d,\"start\":\"%s\",\"end\":\"%s\",\"count\":%d,\"reason\":\"%s\"}",
                        doctorId, startDate, endDate, toCancel.size(), reason));

        log.info("批量取消预约: doctor={}, range={}~{}, count={}", doctorId, startDate, endDate, toCancel.size());
        return toCancel;
    }

    // ── 多资源锁辅助方法 ──────────────────────────────────────

    /**
     * 构建有序锁键列表（按前缀+数字ID排序，防止死锁）
     */
    private List<String> buildOrderedLockKeys(Long slotId, ResourceAllocation allocation) {
        List<LockKeyEntry> entries = new ArrayList<>();
        entries.add(new LockKeyEntry("lock:slot", slotId));

        if (allocation != null) {
            for (Long resSlotId : allocation.getAllResourceSlotIds()) {
                entries.add(new LockKeyEntry("lock:resource", resSlotId));
            }
        }

        entries.sort(Comparator.comparing(LockKeyEntry::prefix).thenComparing(LockKeyEntry::id));

        return entries.stream()
                .map(e -> e.prefix() + ":" + e.id())
                .toList();
    }

    /**
     * 构建取消操作的锁键列表：slot锁 + 已关联的资源锁
     */
    private List<String> buildCancelLockKeys(Appointment appointment) {
        List<LockKeyEntry> entries = new ArrayList<>();
        entries.add(new LockKeyEntry("lock:slot", appointment.getSlotId()));

        if ("JOINT".equals(appointment.getBookingType())) {
            List<AppointmentResource> resources =
                    appointmentResourceMapper.findByAppointment(appointment.getId());
            for (AppointmentResource ar : resources) {
                entries.add(new LockKeyEntry("lock:resource", ar.getResourceSlotId()));
            }
        }

        entries.sort(Comparator.comparing(LockKeyEntry::prefix).thenComparing(LockKeyEntry::id));
        return entries.stream().map(e -> e.prefix() + ":" + e.id()).toList();
    }

    /**
     * 构建改签操作的锁键列表：旧slot + 新slot + 旧资源 + 新资源（去重排序）
     */
    private List<String> buildRescheduleLockKeys(Long oldSlotId, Long newSlotId,
                                                  Appointment original,
                                                  ResourceAllocation newAllocation) {
        List<LockKeyEntry> entries = new ArrayList<>();
        entries.add(new LockKeyEntry("lock:slot", oldSlotId));
        entries.add(new LockKeyEntry("lock:slot", newSlotId));

        // 旧资源锁
        if ("JOINT".equals(original.getBookingType())) {
            List<AppointmentResource> oldResources =
                    appointmentResourceMapper.findByAppointment(original.getId());
            for (AppointmentResource ar : oldResources) {
                entries.add(new LockKeyEntry("lock:resource", ar.getResourceSlotId()));
            }
        }

        // 新资源锁
        if (newAllocation != null) {
            for (Long resSlotId : newAllocation.getAllResourceSlotIds()) {
                entries.add(new LockKeyEntry("lock:resource", resSlotId));
            }
        }

        // 去重 + 排序
        return entries.stream()
                .distinct()
                .sorted(Comparator.comparing(LockKeyEntry::prefix).thenComparing(LockKeyEntry::id))
                .map(e -> e.prefix() + ":" + e.id())
                .toList();
    }

    /**
     * 递归按顺序加锁，全部加锁后执行操作
     * 与DoctorSuspensionServiceImpl的递归锁策略一致
     */
    private <T> T executeWithOrderedLocks(List<String> lockKeys, int index, Supplier<T> action) {
        if (index >= lockKeys.size()) {
            return action.get();
        }
        return lockService.executeWithLock(lockKeys.get(index),
                () -> executeWithOrderedLocks(lockKeys, index + 1, action));
    }

    private record LockKeyEntry(String prefix, Long id) {}

    private String generateNo() {
        return "APT" + LocalDateTime.now().format(NO_FMT) +
                UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
}
