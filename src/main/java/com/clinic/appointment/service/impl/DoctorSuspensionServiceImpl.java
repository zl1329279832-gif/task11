package com.clinic.appointment.service.impl;

import com.clinic.appointment.domain.dto.SuspendRequest;
import com.clinic.appointment.domain.entity.*;
import com.clinic.appointment.domain.enums.AppointmentStatus;
import com.clinic.appointment.domain.enums.SlotStatus;
import com.clinic.appointment.mapper.*;
import com.clinic.appointment.service.AuditService;
import com.clinic.appointment.service.DoctorSuspensionService;
import com.clinic.appointment.service.RedisLockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.*;

/**
 * 停诊服务实现
 *
 * 并发安全设计：
 * - 停诊操作在 lock:suspend:{doctorId} 分布式锁内执行
 * - 所有操作在同一事务中原子提交（TransactionTemplate在锁内）
 * - 先将排班+号源标记为 SUSPENDED 终态，再处理预约
 *   → 停诊后号源为 SUSPENDED 终态，不可被定时任务/候补重新激活
 *   → MySQL行锁保证 suspendSlots 与并发 book 的 casBook 互斥
 * - 预约取消只改预约状态，不再释放号源（号源已被 suspendSlots 处理）
 */
@Slf4j
@Service
public class DoctorSuspensionServiceImpl implements DoctorSuspensionService {

    private final DoctorSuspensionMapper suspensionMapper;
    private final DoctorMapper doctorMapper;
    private final DoctorScheduleMapper scheduleMapper;
    private final ScheduleSlotMapper slotMapper;
    private final AppointmentMapper appointmentMapper;
    private final RedisLockService lockService;
    private final AuditService auditService;
    private final TransactionTemplate txTemplate;

    public DoctorSuspensionServiceImpl(DoctorSuspensionMapper suspensionMapper,
                                       DoctorMapper doctorMapper,
                                       DoctorScheduleMapper scheduleMapper,
                                       ScheduleSlotMapper slotMapper,
                                       AppointmentMapper appointmentMapper,
                                       RedisLockService lockService,
                                       AuditService auditService,
                                       PlatformTransactionManager txManager) {
        this.suspensionMapper = suspensionMapper;
        this.doctorMapper = doctorMapper;
        this.scheduleMapper = scheduleMapper;
        this.slotMapper = slotMapper;
        this.appointmentMapper = appointmentMapper;
        this.lockService = lockService;
        this.auditService = auditService;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    @Override
    public Map<String, Object> suspend(SuspendRequest request) {
        Long doctorId = request.getDoctorId();
        LocalDate startDate = request.getStartDate();
        LocalDate endDate = request.getEndDate();
        String migrateType = request.getMigrateType();

        String lockKey = "lock:suspend:" + doctorId;
        return lockService.executeWithLock(lockKey, () ->
            // 所有操作在同一事务中原子提交
            txTemplate.execute(status -> {
                Doctor doctor = doctorMapper.selectById(doctorId);
                if (doctor == null) {
                    throw new com.clinic.appointment.exception.BusinessException(
                            "DOCTOR_NOT_FOUND", "医生不存在");
                }

                // 1. 创建停诊记录
                DoctorSuspension suspension = new DoctorSuspension();
                suspension.setDoctorId(doctorId);
                suspension.setStartDate(startDate);
                suspension.setEndDate(endDate);
                suspension.setReason(request.getReason() != null ? request.getReason() : "");
                suspension.setMigrateType(migrateType);
                suspension.setStatus("PROCESSING");
                suspensionMapper.insert(suspension);

                // 2. 先冻结排班和号源（防止并发book/backfill操作）
                //    MySQL行锁保证 suspendSlots 与并发 casBook 互斥
                scheduleMapper.batchUpdateStatus(doctorId, startDate, endDate, "SUSPENDED");
                int suspendedCount = slotMapper.suspendSlots(doctorId, startDate, endDate);
                log.info("停诊冻结: doctor={}, slots={}", doctorId, suspendedCount);

                // 3. 查找受影响的预约（号源已冻结，不会有新预约产生）
                List<Appointment> affected = appointmentMapper
                        .findActiveByDoctorFromDate(doctorId, startDate);
                affected = affected.stream()
                        .filter(a -> !a.getSlotDate().isAfter(endDate))
                        .toList();

                log.info("停诊处理: doctor={}, range={}~{}, affected={}",
                        doctorId, startDate, endDate, affected.size());

                int cancelledCount = 0;
                int migratedCount = 0;
                int failedCount = 0;
                List<String> details = new ArrayList<>();

                if ("CANCEL".equals(migrateType)) {
                    // ── 取消模式：只改预约状态，号源已被 suspendSlots 处理 ──
                    String reason = "医生停诊: " + suspension.getReason();
                    for (Appointment appt : affected) {
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
                        cancelledCount++;
                    }
                    details.add(String.format("取消%d个预约", cancelledCount));

                } else if ("MIGRATE".equals(migrateType)) {
                    // ── 迁移模式 ──
                    List<Doctor> alternatives = doctorMapper
                            .findActiveByDepartment(doctor.getDepartmentId());
                    alternatives = alternatives.stream()
                            .filter(d -> !d.getId().equals(doctorId))
                            .toList();

                    if (alternatives.isEmpty()) {
                        log.warn("无可迁移医生，降级为取消: doctor={}", doctorId);
                        String reason = "医生停诊(无可迁移医生): " + suspension.getReason();
                        for (Appointment appt : affected) {
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
                            cancelledCount++;
                        }
                        details.add("无可迁移医生，降级取消" + cancelledCount + "个预约");
                    } else {
                        for (Appointment appt : affected) {
                            Appointment fresh = appointmentMapper.selectById(appt.getId());
                            if (fresh == null) continue;
                            String apptStatus = fresh.getStatus();
                            if (!AppointmentStatus.PENDING.name().equals(apptStatus) &&
                                !AppointmentStatus.CONFIRMED.name().equals(apptStatus)) {
                                continue;
                            }

                            try {
                                boolean migrated = migrateAppointment(fresh, alternatives);
                                if (migrated) {
                                    migratedCount++;
                                } else {
                                    cancelSingleAppointment(fresh,
                                            "医生停诊且无法迁移: " + suspension.getReason());
                                    cancelledCount++;
                                }
                            } catch (Exception e) {
                                log.error("迁移预约失败: appointmentId={}, error={}",
                                        fresh.getId(), e.getMessage());
                                cancelSingleAppointment(fresh,
                                        "医生停诊迁移失败: " + e.getMessage());
                                cancelledCount++;
                                failedCount++;
                            }
                        }
                        details.add(String.format("迁移%d个, 取消%d个, 失败%d个",
                                migratedCount, cancelledCount, failedCount));
                    }
                }

                // 4. 更新停诊状态
                suspension.setStatus("COMPLETED");
                suspensionMapper.updateById(suspension);

                auditService.log("DOCTOR_SUSPENSION", "SUSPENSION", suspension.getId(),
                        String.format("{\"doctorId\":%d,\"type\":\"%s\",\"cancelled\":%d,\"migrated\":%d}",
                                doctorId, migrateType, cancelledCount, migratedCount));

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("suspensionId", suspension.getId());
                result.put("doctorId", doctorId);
                result.put("doctorName", doctor.getName());
                result.put("range", startDate + " ~ " + endDate);
                result.put("migrateType", migrateType);
                result.put("cancelledCount", cancelledCount);
                result.put("migratedCount", migratedCount);
                result.put("failedCount", failedCount);
                result.put("details", details);

                log.info("停诊处理完成: {}", result);
                return result;
            })
        );
    }

    /**
     * 尝试将预约迁移到同科室其他医生
     *
     * 注意：原号源已被 suspendSlots 标记为 SUSPENDED，不需要再释放。
     * 替代号源通过 CAS 占用，MySQL行锁保证与并发 book 操作互斥。
     */
    private boolean migrateAppointment(Appointment original, List<Doctor> alternatives) {
        for (Doctor alt : alternatives) {
            // 使用含排班状态校验的查询，排除替代医生的已停诊号源
            List<ScheduleSlot> altSlots = slotMapper.findAvailableWithScheduleCheck(
                    alt.getId(), original.getSlotDate());
            if (altSlots.isEmpty()) continue;

            for (ScheduleSlot targetSlot : altSlots) {
                // 创建新预约
                Appointment newAppt = new Appointment();
                newAppt.setAppointmentNo("MIG" + System.currentTimeMillis()
                        + UUID.randomUUID().toString().substring(0, 4).toUpperCase());
                newAppt.setPatientId(original.getPatientId());
                newAppt.setPatientName(original.getPatientName());
                newAppt.setDoctorId(alt.getId());
                newAppt.setDepartmentId(original.getDepartmentId());
                newAppt.setSlotId(targetSlot.getId());
                newAppt.setSlotDate(targetSlot.getSlotDate());
                newAppt.setSlotTime(targetSlot.getSlotTime());
                newAppt.setStatus("CONFIRMED");
                newAppt.setSource(original.getSource());
                newAppt.setOriginalId(original.getId());
                appointmentMapper.insert(newAppt);

                // CAS占用替代号源
                int updated = slotMapper.casBook(targetSlot.getId(), newAppt.getId(), targetSlot.getVersion());
                if (updated == 0) {
                    // 号源已被占，标记此预约为失败，尝试下一个号源
                    newAppt.setStatus("CANCELLED");
                    newAppt.setCancelReason("迁移失败-号源已被占");
                    appointmentMapper.updateById(newAppt);
                    continue;
                }

                // 号源占用成功，标记原预约为已改签
                original.setStatus("RESCHEDULED");
                original.setCancelReason("停诊迁移至" + alt.getName());
                appointmentMapper.updateById(original);

                auditService.log("MIGRATE_APPOINTMENT", "APPOINTMENT", newAppt.getId(),
                        String.format("{\"fromDoctor\":%d,\"toDoctor\":%d,\"fromAppt\":%d}",
                                original.getDoctorId(), alt.getId(), original.getId()));

                log.info("预约迁移成功: patient={}, from={} to={}, date={}",
                        original.getPatientId(), original.getDoctorId(), alt.getId(),
                        original.getSlotDate());

                return true;
            }
        }
        return false;
    }

    /**
     * 取消单个预约（只改预约状态，号源由 suspendSlots 统一处理）
     */
    private void cancelSingleAppointment(Appointment appt, String reason) {
        appt.setStatus("CANCELLED");
        appt.setCancelReason(reason);
        appointmentMapper.updateById(appt);
    }

    @Override
    public DoctorSuspension getById(Long id) {
        return suspensionMapper.selectById(id);
    }
}
