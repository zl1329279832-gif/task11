package com.clinic.appointment.service.impl;

import com.clinic.appointment.domain.dto.*;
import com.clinic.appointment.domain.entity.*;
import com.clinic.appointment.domain.enums.AppointmentStatus;
import com.clinic.appointment.domain.enums.AppointmentType;
import com.clinic.appointment.domain.enums.ResourceStatus;
import com.clinic.appointment.domain.enums.ResourceType;
import com.clinic.appointment.domain.enums.SlotStatus;
import com.clinic.appointment.exception.BusinessException;
import com.clinic.appointment.mapper.*;
import com.clinic.appointment.service.AuditService;
import com.clinic.appointment.service.MultiResourceBookingService;
import com.clinic.appointment.service.RedisLockService;
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
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultiResourceBookingServiceImpl implements MultiResourceBookingService {

    private final AppointmentMapper appointmentMapper;
    private final ScheduleSlotMapper slotMapper;
    private final DoctorScheduleMapper scheduleMapper;
    private final ResourceAvailabilityMapper resourceAvailabilityMapper;
    private final AppointmentResourceMapper appointmentResourceMapper;
    private final EquipmentMapper equipmentMapper;
    private final RedisLockService lockService;
    private final AuditService auditService;

    @Autowired
    @Lazy
    private WaitlistService waitlistService;

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int MAX_APPOINTMENTS_PER_DAY = 5;

    // ────────────────────────────────────────────
    // 联合预约
    // ────────────────────────────────────────────

    @Override
    @Transactional
    public JointBookingResult jointBook(JointBookRequest request) {
        // Phase 1: 预检查（无锁）
        ScheduleSlot slot = slotMapper.selectById(request.getSlotId());
        if (slot == null || !SlotStatus.AVAILABLE.name().equals(slot.getStatus())) {
            throw BusinessException.slotNotAvailable();
        }

        // 患者当日预约数检查
        validatePatientLimits(request.getPatientId(), slot.getSlotDate());

        // Phase 2: 解析资源候选
        ResourceCandidates candidates = resolveCandidates(
                slot.getDepartmentId(), slot.getSlotDate(), slot.getSlotTime(),
                request.getExamType(), request.getPreferredRoomId(),
                request.getPreferredEquipmentId(), request.getPreferredNursingStaffId());

        // Phase 3: 构建排序锁集合
        List<String> lockKeys = buildSortedLockKeys(request.getSlotId(), candidates);

        // Phase 4: 递归获取所有锁并执行原子操作
        return acquireResourceLocks(lockKeys, 0, () ->
                executeJointBooking(request, slot, candidates));
    }

    private JointBookingResult executeJointBooking(JointBookRequest request,
                                                    ScheduleSlot slot,
                                                    ResourceCandidates candidates) {
        // 1. 锁内双重检查
        ScheduleSlot freshSlot = slotMapper.selectById(slot.getId());
        if (freshSlot == null || !SlotStatus.AVAILABLE.name().equals(freshSlot.getStatus())) {
            throw BusinessException.slotNotAvailable();
        }
        recheckResource(candidates.room);
        recheckResource(candidates.equipment);
        recheckResource(candidates.nursing);

        // 1.5 锁内重新校验患者当日预约数（防止并发突破上限）
        validatePatientLimits(request.getPatientId(), freshSlot.getSlotDate());

        // 2. 创建预约记录
        Appointment appointment = new Appointment();
        appointment.setAppointmentNo(generateJointNo());
        appointment.setPatientId(request.getPatientId());
        appointment.setPatientName(request.getPatientName() != null ? request.getPatientName() : "");
        appointment.setDoctorId(freshSlot.getDoctorId());
        appointment.setDepartmentId(freshSlot.getDepartmentId());
        appointment.setSlotId(freshSlot.getId());
        appointment.setSlotDate(freshSlot.getSlotDate());
        appointment.setSlotTime(freshSlot.getSlotTime());
        appointment.setStatus(AppointmentStatus.CONFIRMED.name());
        appointment.setSource("ONLINE");
        appointment.setAppointmentType(AppointmentType.EXAM.name());
        appointmentMapper.insert(appointment);

        // 3. CAS占用号源
        int slotUpdated = slotMapper.casBook(freshSlot.getId(), appointment.getId(), freshSlot.getVersion());
        if (slotUpdated == 0) {
            throw BusinessException.slotNotAvailable();
        }

        // 4. CAS占用资源窗口
        casBookResource(candidates.room, appointment.getId(), "检查室");
        casBookResource(candidates.equipment, appointment.getId(), "设备");
        casBookResource(candidates.nursing, appointment.getId(), "护理人员");

        // 5. 插入关联记录
        List<AppointmentResource> resources = new ArrayList<>();
        resources.add(createAppointmentResource(appointment.getId(), candidates.room));
        resources.add(createAppointmentResource(appointment.getId(), candidates.equipment));
        resources.add(createAppointmentResource(appointment.getId(), candidates.nursing));

        // 6. 更新排班已预约数
        DoctorSchedule schedule = scheduleMapper.selectById(freshSlot.getScheduleId());
        if (schedule != null) {
            schedule.setBookedSlots(schedule.getBookedSlots() + 1);
            scheduleMapper.updateById(schedule);
        }

        // 7. 审计日志
        auditService.log("JOINT_BOOK", "APPOINTMENT", appointment.getId(),
                String.format("{\"patientId\":%d,\"slotId\":%d,\"examType\":\"%s\"," +
                                "\"room\":%d,\"equipment\":%d,\"nursing\":%d}",
                        request.getPatientId(), freshSlot.getId(), request.getExamType(),
                        candidates.room.getResourceId(),
                        candidates.equipment.getResourceId(),
                        candidates.nursing.getResourceId()));

        log.info("联合预约成功: no={}, patient={}, slot={}, room={}, equip={}, nurse={}",
                appointment.getAppointmentNo(), request.getPatientId(),
                freshSlot.getId(), candidates.room.getResourceId(),
                candidates.equipment.getResourceId(), candidates.nursing.getResourceId());

        return new JointBookingResult(appointment, resources);
    }

    // ────────────────────────────────────────────
    // 联合取消
    // ────────────────────────────────────────────

    @Override
    @Transactional
    public Appointment jointCancel(CancelRequest request) {
        Appointment appointment = appointmentMapper.selectById(request.getAppointmentId());
        if (appointment == null) {
            throw BusinessException.appointmentNotFound();
        }

        String status = appointment.getStatus();
        // 幂等：已取消的预约直接返回
        if (AppointmentStatus.CANCELLED.name().equals(status)) {
            log.info("联合预约已取消，幂等返回(预检查): id={}", appointment.getId());
            return appointment;
        }
        if (!AppointmentStatus.PENDING.name().equals(status) &&
            !AppointmentStatus.CONFIRMED.name().equals(status)) {
            throw BusinessException.invalidStatusTransition(status, "CANCELLED");
        }

        // 获取关联资源
        List<AppointmentResource> resources = appointmentResourceMapper.findByAppointment(appointment.getId());

        // 构建锁集合
        List<String> lockKeys = new ArrayList<>();
        lockKeys.add("lock:slot:" + appointment.getSlotId());
        for (AppointmentResource ar : resources) {
            // 需要通过availabilityId找到ResourceAvailability来获取锁
            ResourceAvailability avail = resourceAvailabilityMapper.selectById(ar.getAvailabilityId());
            if (avail != null) {
                lockKeys.add("lock:resource:" + ar.getResourceType() + ":" + ar.getAvailabilityId());
            }
        }
        lockKeys = sortedDistinct(lockKeys);

        return acquireResourceLocks(lockKeys, 0, () ->
                executeJointCancel(appointment, resources, request));
    }

    private Appointment executeJointCancel(Appointment appointment,
                                            List<AppointmentResource> resources,
                                            CancelRequest request) {
        // 锁内重新校验
        Appointment freshAppt = appointmentMapper.selectById(appointment.getId());
        if (freshAppt == null) {
            throw BusinessException.appointmentNotFound();
        }
        String freshStatus = freshAppt.getStatus();
        // 幂等：已取消的预约直接返回，不抛异常
        if (AppointmentStatus.CANCELLED.name().equals(freshStatus)) {
            log.info("联合预约已取消，幂等返回: id={}", freshAppt.getId());
            return freshAppt;
        }
        if (!AppointmentStatus.PENDING.name().equals(freshStatus) &&
            !AppointmentStatus.CONFIRMED.name().equals(freshStatus)) {
            throw BusinessException.invalidStatusTransition(freshStatus, "CANCELLED");
        }

        // 1. 更新预约状态
        freshAppt.setStatus(AppointmentStatus.CANCELLED.name());
        freshAppt.setCancelReason(request.getReason() != null ? request.getReason() : "");
        appointmentMapper.updateById(freshAppt);

        // 2. 释放号源
        ScheduleSlot slot = slotMapper.selectById(freshAppt.getSlotId());
        if (slot != null && SlotStatus.BOOKED.name().equals(slot.getStatus())) {
            int released = slotMapper.casRelease(slot.getId(), slot.getVersion());
            if (released == 0) {
                throw BusinessException.of("SLOT_RELEASE_FAILED", "号源释放失败，请重试");
            }

            DoctorSchedule schedule = scheduleMapper.selectById(slot.getScheduleId());
            if (schedule != null && schedule.getBookedSlots() > 0) {
                schedule.setBookedSlots(schedule.getBookedSlots() - 1);
                scheduleMapper.updateById(schedule);
            }
        }

        // 3. 释放所有资源窗口
        for (AppointmentResource ar : resources) {
            ResourceAvailability avail = resourceAvailabilityMapper.selectById(ar.getAvailabilityId());
            if (avail != null && ResourceStatus.BOOKED.name().equals(avail.getStatus())) {
                int released = resourceAvailabilityMapper.casRelease(avail.getId(), avail.getVersion());
                if (released == 0) {
                    log.warn("资源释放CAS失败(将由事务回滚): availabilityId={}", ar.getAvailabilityId());
                    throw BusinessException.resourceCasFailed(ar.getResourceType());
                }
            }
        }

        // 4. 删除关联记录
        appointmentResourceMapper.deleteByAppointment(freshAppt.getId());

        // 5. 审计日志
        auditService.log("JOINT_CANCEL", "APPOINTMENT", freshAppt.getId(),
                String.format("{\"reason\":\"%s\"}", request.getReason()));

        log.info("联合取消成功: no={}", freshAppt.getAppointmentNo());

        // 6. 事务提交后触发候补补位
        final Long doctorId = freshAppt.getDoctorId();
        final LocalDate slotDate = freshAppt.getSlotDate();
        final LocalTime slotTime = freshAppt.getSlotTime();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        waitlistService.triggerBackfill(doctorId, slotDate, slotTime);
                    } catch (Exception e) {
                        log.error("联合取消后候补补位触发失败: {}", e.getMessage());
                    }
                }
            });
        }

        return freshAppt;
    }

    // ────────────────────────────────────────────
    // 联合改约
    // ────────────────────────────────────────────

    @Override
    @Transactional
    public JointBookingResult jointReschedule(JointRescheduleRequest request) {
        Appointment original = appointmentMapper.selectById(request.getAppointmentId());
        if (original == null) {
            throw BusinessException.appointmentNotFound();
        }
        if (!AppointmentType.EXAM.name().equals(original.getAppointmentType())) {
            throw BusinessException.notJointAppointment();
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

        // 旧资源
        List<AppointmentResource> oldResources = appointmentResourceMapper.findByAppointment(original.getId());

        // 改约时从原资源推导 preferred（如果请求未指定）
        Long preferredRoomId = request.getPreferredRoomId();
        Long preferredEquipmentId = request.getPreferredEquipmentId();
        Long preferredNursingId = request.getPreferredNursingStaffId();
        for (AppointmentResource ar : oldResources) {
            if (preferredRoomId == null && ResourceType.EXAM_ROOM.name().equals(ar.getResourceType())) {
                preferredRoomId = ar.getResourceId();
            }
            if (preferredEquipmentId == null && ResourceType.EQUIPMENT.name().equals(ar.getResourceType())) {
                preferredEquipmentId = ar.getResourceId();
            }
            if (preferredNursingId == null && ResourceType.NURSING_STAFF.name().equals(ar.getResourceType())) {
                preferredNursingId = ar.getResourceId();
            }
        }

        // 新资源候选
        ResourceCandidates newCandidates = resolveCandidates(
                newSlot.getDepartmentId(), newSlot.getSlotDate(), newSlot.getSlotTime(),
                null, preferredRoomId,
                preferredEquipmentId, preferredNursingId);

        // 构建联合锁集合（旧+新，去重排序）
        Set<String> lockKeySet = new TreeSet<>();
        lockKeySet.add("lock:slot:" + original.getSlotId());
        lockKeySet.add("lock:slot:" + newSlot.getId());
        for (AppointmentResource ar : oldResources) {
            lockKeySet.add("lock:resource:" + ar.getResourceType() + ":" + ar.getAvailabilityId());
        }
        lockKeySet.add("lock:resource:" + newCandidates.room.getResourceType() + ":" + newCandidates.room.getId());
        lockKeySet.add("lock:resource:" + newCandidates.equipment.getResourceType() + ":" + newCandidates.equipment.getId());
        lockKeySet.add("lock:resource:" + newCandidates.nursing.getResourceType() + ":" + newCandidates.nursing.getId());

        List<String> sortedKeys = new ArrayList<>(lockKeySet);

        return acquireResourceLocks(sortedKeys, 0, () ->
                executeJointReschedule(original, newSlot, oldResources, newCandidates, request));
    }

    private JointBookingResult executeJointReschedule(Appointment original,
                                                       ScheduleSlot newSlot,
                                                       List<AppointmentResource> oldResources,
                                                       ResourceCandidates newCandidates,
                                                       JointRescheduleRequest request) {
        // 1. 锁内重新校验
        ScheduleSlot freshNew = slotMapper.selectById(newSlot.getId());
        if (freshNew == null || !SlotStatus.AVAILABLE.name().equals(freshNew.getStatus())) {
            throw BusinessException.slotNotAvailable();
        }
        recheckResource(newCandidates.room);
        recheckResource(newCandidates.equipment);
        recheckResource(newCandidates.nursing);

        // 2. 释放旧号源（检查返回值，失败则回滚）
        ScheduleSlot oldSlot = slotMapper.selectById(original.getSlotId());
        if (oldSlot != null && SlotStatus.BOOKED.name().equals(oldSlot.getStatus())) {
            int released = slotMapper.casRelease(oldSlot.getId(), oldSlot.getVersion());
            if (released == 0) {
                throw BusinessException.of("SLOT_RELEASE_FAILED", "旧号源释放失败，请重试");
            }
        }

        // 3. 释放旧资源（检查返回值，失败则回滚）
        for (AppointmentResource ar : oldResources) {
            ResourceAvailability avail = resourceAvailabilityMapper.selectById(ar.getAvailabilityId());
            if (avail != null && ResourceStatus.BOOKED.name().equals(avail.getStatus())) {
                int released = resourceAvailabilityMapper.casRelease(avail.getId(), avail.getVersion());
                if (released == 0) {
                    throw BusinessException.resourceCasFailed(ar.getResourceType());
                }
            }
        }

        // 4. 标记旧预约为已改签
        original.setStatus(AppointmentStatus.RESCHEDULED.name());
        original.setCancelReason(request.getReason() != null ? request.getReason() : "改签");
        appointmentMapper.updateById(original);

        // 5. 创建新预约
        Appointment newAppointment = new Appointment();
        newAppointment.setAppointmentNo(generateJointNo());
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
        newAppointment.setAppointmentType(AppointmentType.EXAM.name());
        appointmentMapper.insert(newAppointment);

        // 6. CAS占用新号源
        int slotUpdated = slotMapper.casBook(freshNew.getId(), newAppointment.getId(), freshNew.getVersion());
        if (slotUpdated == 0) {
            throw BusinessException.slotNotAvailable();
        }

        // 7. CAS占用新资源
        casBookResource(newCandidates.room, newAppointment.getId(), "检查室");
        casBookResource(newCandidates.equipment, newAppointment.getId(), "设备");
        casBookResource(newCandidates.nursing, newAppointment.getId(), "护理人员");

        // 8. 更新关联记录
        appointmentResourceMapper.deleteByAppointment(original.getId());
        List<AppointmentResource> newResourceRecords = new ArrayList<>();
        newResourceRecords.add(createAppointmentResource(newAppointment.getId(), newCandidates.room));
        newResourceRecords.add(createAppointmentResource(newAppointment.getId(), newCandidates.equipment));
        newResourceRecords.add(createAppointmentResource(newAppointment.getId(), newCandidates.nursing));

        // 9. 审计日志
        auditService.log("JOINT_RESCHEDULE", "APPOINTMENT", newAppointment.getId(),
                String.format("{\"fromId\":%d,\"fromSlot\":%d,\"toSlot\":%d}",
                        original.getId(), original.getSlotId(), newSlot.getId()));

        log.info("联合改约成功: old={}, new={}, patient={}",
                original.getAppointmentNo(), newAppointment.getAppointmentNo(),
                original.getPatientId());

        // 10. 事务提交后触发旧号源候补
        final Long oldDoctorId = original.getDoctorId();
        final LocalDate oldDate = original.getSlotDate();
        final LocalTime oldTime = original.getSlotTime();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        waitlistService.triggerBackfill(oldDoctorId, oldDate, oldTime);
                    } catch (Exception e) {
                        log.error("联合改约后候补补位触发失败: {}", e.getMessage());
                    }
                }
            });
        }

        return new JointBookingResult(newAppointment, newResourceRecords);
    }

    // ────────────────────────────────────────────
    // 辅助方法
    // ────────────────────────────────────────────

    private void validatePatientLimits(Long patientId, LocalDate date) {
        long count = appointmentMapper.countByPatientAndDate(patientId, date);
        if (count >= MAX_APPOINTMENTS_PER_DAY) {
            throw BusinessException.patientLimitExceeded(MAX_APPOINTMENTS_PER_DAY);
        }
    }

    /**
     * 解析资源候选：查找覆盖目标时间的可用诊室/设备/护理
     */
    private ResourceCandidates resolveCandidates(Long deptId, LocalDate date, LocalTime time,
                                                  String examType,
                                                  Long preferredRoomId,
                                                  Long preferredEquipmentId,
                                                  Long preferredNursingId) {
        // 诊室
        ResourceAvailability room;
        if (preferredRoomId != null) {
            List<ResourceAvailability> windows = resourceAvailabilityMapper.findAvailableWindow(
                    ResourceType.EXAM_ROOM.name(), preferredRoomId, date, time);
            room = windows.isEmpty() ? null : windows.get(0);
        } else {
            List<ResourceAvailability> windows = resourceAvailabilityMapper.findAvailableRoomInDept(
                    deptId, date, time);
            room = windows.isEmpty() ? null : windows.get(0);
        }
        if (room == null) {
            throw BusinessException.resourceUnavailable("检查室");
        }

        // 设备
        ResourceAvailability equipment;
        if (preferredEquipmentId != null) {
            // 校验指定设备是否处于激活状态（防止命中已停用设备）
            Equipment preferredEquip = equipmentMapper.selectById(preferredEquipmentId);
            if (preferredEquip == null || !"ACTIVE".equals(preferredEquip.getStatus())) {
                throw BusinessException.resourceUnavailable("设备");
            }
            List<ResourceAvailability> windows = resourceAvailabilityMapper.findAvailableWindow(
                    ResourceType.EQUIPMENT.name(), preferredEquipmentId, date, time);
            equipment = windows.isEmpty() ? null : windows.get(0);
        } else if (examType != null) {
            List<ResourceAvailability> windows = resourceAvailabilityMapper.findAvailableEquipmentInDept(
                    deptId, examType, date, time);
            equipment = windows.isEmpty() ? null : windows.get(0);
        } else {
            // 改约场景：从原预约推导设备类型
            List<ResourceAvailability> windows = resourceAvailabilityMapper.findAvailableWindow(
                    ResourceType.EQUIPMENT.name(), preferredEquipmentId != null ? preferredEquipmentId : 0L, date, time);
            equipment = windows.isEmpty() ? null : windows.get(0);
        }
        if (equipment == null) {
            throw BusinessException.resourceUnavailable("设备");
        }

        // 护理
        ResourceAvailability nursing;
        if (preferredNursingId != null) {
            List<ResourceAvailability> windows = resourceAvailabilityMapper.findAvailableWindow(
                    ResourceType.NURSING_STAFF.name(), preferredNursingId, date, time);
            nursing = windows.isEmpty() ? null : windows.get(0);
        } else {
            List<ResourceAvailability> windows = resourceAvailabilityMapper.findAvailableNursingInDept(
                    deptId, date, time);
            nursing = windows.isEmpty() ? null : windows.get(0);
        }
        if (nursing == null) {
            throw BusinessException.resourceUnavailable("护理人员");
        }

        return new ResourceCandidates(room, equipment, nursing);
    }

    private List<String> buildSortedLockKeys(Long slotId, ResourceCandidates candidates) {
        List<String> keys = new ArrayList<>();
        keys.add("lock:slot:" + slotId);
        keys.add("lock:resource:" + candidates.room.getResourceType() + ":" + candidates.room.getId());
        keys.add("lock:resource:" + candidates.equipment.getResourceType() + ":" + candidates.equipment.getId());
        keys.add("lock:resource:" + candidates.nursing.getResourceType() + ":" + candidates.nursing.getId());
        return sortedDistinct(keys);
    }

    private List<String> sortedDistinct(List<String> keys) {
        TreeSet<String> set = new TreeSet<>(keys);
        return new ArrayList<>(set);
    }

    /**
     * 递归获取多资源锁（按排序顺序嵌套）
     */
    private <T> T acquireResourceLocks(List<String> sortedKeys, int index, Supplier<T> action) {
        if (index >= sortedKeys.size()) {
            return action.get();
        }
        return lockService.executeWithLock(sortedKeys.get(index), 5, 30, TimeUnit.SECONDS,
                () -> acquireResourceLocks(sortedKeys, index + 1, action));
    }

    private void recheckResource(ResourceAvailability avail) {
        ResourceAvailability fresh = resourceAvailabilityMapper.selectById(avail.getId());
        if (fresh == null || !ResourceStatus.AVAILABLE.name().equals(fresh.getStatus())) {
            throw BusinessException.resourceUnavailable(avail.getResourceType());
        }
        // 更新version以供后续CAS使用
        avail.setVersion(fresh.getVersion());
        avail.setStatus(fresh.getStatus());
    }

    private void casBookResource(ResourceAvailability avail, Long appointmentId, String displayName) {
        int updated = resourceAvailabilityMapper.casBook(avail.getId(), appointmentId, avail.getVersion());
        if (updated == 0) {
            throw BusinessException.resourceCasFailed(displayName);
        }
    }

    private AppointmentResource createAppointmentResource(Long appointmentId, ResourceAvailability avail) {
        AppointmentResource ar = new AppointmentResource();
        ar.setAppointmentId(appointmentId);
        ar.setResourceType(avail.getResourceType());
        ar.setResourceId(avail.getResourceId());
        ar.setAvailabilityId(avail.getId());
        appointmentResourceMapper.insert(ar);
        return ar;
    }

    private String generateJointNo() {
        return "JPT" + LocalDateTime.now().format(NO_FMT) +
                UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    /**
     * 资源候选内部记录
     */
    private record ResourceCandidates(
            ResourceAvailability room,
            ResourceAvailability equipment,
            ResourceAvailability nursing
    ) {}
}
