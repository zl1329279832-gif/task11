package com.clinic.appointment.service.impl;

import com.clinic.appointment.domain.dto.SuspendRequest;
import com.clinic.appointment.domain.entity.*;
import com.clinic.appointment.domain.enums.SlotStatus;
import com.clinic.appointment.mapper.*;
import com.clinic.appointment.service.AuditService;
import com.clinic.appointment.service.DoctorSuspensionService;
import com.clinic.appointment.service.RedisLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DoctorSuspensionServiceImpl implements DoctorSuspensionService {

    private final DoctorSuspensionMapper suspensionMapper;
    private final DoctorMapper doctorMapper;
    private final DoctorScheduleMapper scheduleMapper;
    private final ScheduleSlotMapper slotMapper;
    private final AppointmentMapper appointmentMapper;
    private final RedisLockService lockService;
    private final AuditService auditService;

    @Override
    @Transactional
    public Map<String, Object> suspend(SuspendRequest request) {
        Long doctorId = request.getDoctorId();
        LocalDate startDate = request.getStartDate();
        LocalDate endDate = request.getEndDate();
        String migrateType = request.getMigrateType();

        // 1. 外层锁：防止同一医生并发停诊
        String suspendLockKey = "lock:suspend:" + doctorId;
        return lockService.executeWithLock(suspendLockKey, 10, 60, TimeUnit.SECONDS, () -> {

            // 2. 获取所有涉及日期的backfill锁，防止定时补位任务在停诊期间重新预约号源
            //    按日期顺序加锁避免死锁
            List<LocalDate> dates = new ArrayList<>();
            for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
                dates.add(d);
            }

            return acquireBackfillLocks(doctorId, dates, 0, () -> {

                Doctor doctor = doctorMapper.selectById(doctorId);
                if (doctor == null) {
                    throw new com.clinic.appointment.exception.BusinessException(
                            "DOCTOR_NOT_FOUND", "医生不存在");
                }

                // 3. 创建停诊记录
                DoctorSuspension suspension = new DoctorSuspension();
                suspension.setDoctorId(doctorId);
                suspension.setStartDate(startDate);
                suspension.setEndDate(endDate);
                suspension.setReason(request.getReason() != null ? request.getReason() : "");
                suspension.setMigrateType(migrateType);
                suspension.setStatus("PROCESSING");
                suspensionMapper.insert(suspension);

                // ── 核心修复：先将所有号源原子标记为SUSPENDED ──
                // 这确保定时任务waitlistBackfillCompensation看不到AVAILABLE号源，
                // 从而不会在停诊处理期间重新将号源预约出去
                int suspendedSlots = slotMapper.batchSuspend(doctorId, startDate, endDate);
                log.info("停诊-号源批量SUSPENDED: doctor={}, range={}~{}, count={}",
                        doctorId, startDate, endDate, suspendedSlots);

                int cancelledCount = 0;
                int migratedCount = 0;
                int failedCount = 0;
                List<String> details = new ArrayList<>();

                // 4. 查找受影响的预约（号源已被标记为SUSPENDED，但预约记录仍为活跃状态）
                List<Appointment> affected = appointmentMapper
                        .findActiveByDoctorFromDate(doctorId, startDate);
                affected = affected.stream()
                        .filter(a -> !a.getSlotDate().isAfter(endDate))
                        .toList();

                log.info("停诊处理: doctor={}, range={}~{}, affected={}",
                        doctorId, startDate, endDate, affected.size());

                if ("CANCEL".equals(migrateType)) {
                    // ── 取消模式：直接取消预约（号源已SUSPENDED，无需释放） ──
                    String reason = "医生停诊: " + suspension.getReason();
                    for (Appointment appt : affected) {
                        appt.setStatus("CANCELLED");
                        appt.setCancelReason(reason);
                        appointmentMapper.updateById(appt);
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
                            appt.setStatus("CANCELLED");
                            appt.setCancelReason(reason);
                            appointmentMapper.updateById(appt);
                            cancelledCount++;
                        }
                        details.add("无可迁移医生，降级取消" + cancelledCount + "个预约");
                    } else {
                        for (Appointment appt : affected) {
                            try {
                                boolean migrated = migrateAppointment(appt, alternatives);
                                if (migrated) {
                                    migratedCount++;
                                } else {
                                    appt.setStatus("CANCELLED");
                                    appt.setCancelReason("医生停诊且无法迁移: " + suspension.getReason());
                                    appointmentMapper.updateById(appt);
                                    cancelledCount++;
                                }
                            } catch (Exception e) {
                                log.error("迁移预约失败: appointmentId={}, error={}",
                                        appt.getId(), e.getMessage());
                                appt.setStatus("CANCELLED");
                                appt.setCancelReason("医生停诊迁移失败: " + e.getMessage());
                                appointmentMapper.updateById(appt);
                                cancelledCount++;
                                failedCount++;
                            }
                        }
                        details.add(String.format("迁移%d个, 取消%d个, 失败%d个",
                                migratedCount, cancelledCount, failedCount));
                    }
                }

                // 5. 更新排班状态
                scheduleMapper.batchUpdateStatus(doctorId, startDate, "SUSPENDED");

                // 6. 更新停诊状态
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
            });
        });
    }

    /**
     * 递归获取多日期的backfill锁（按顺序加锁避免死锁）
     */
    private <T> T acquireBackfillLocks(Long doctorId, List<LocalDate> dates, int index,
                                        java.util.function.Supplier<T> action) {
        if (index >= dates.size()) {
            return action.get();
        }
        String lockKey = "lock:backfill:" + doctorId + ":" + dates.get(index);
        return lockService.executeWithLock(lockKey, 10, 60, TimeUnit.SECONDS,
                () -> acquireBackfillLocks(doctorId, dates, index + 1, action));
    }

    /**
     * 尝试将预约迁移到同科室其他医生
     * 注意：原号源已被标记为SUSPENDED，无需释放
     */
    private boolean migrateAppointment(Appointment original, List<Doctor> alternatives) {
        for (Doctor alt : alternatives) {
            List<ScheduleSlot> altSlots = slotMapper.findAvailable(alt.getId(), original.getSlotDate());
            if (altSlots.isEmpty()) continue;

            ScheduleSlot targetSlot = altSlots.get(0);
            try {
                // 标记原预约为已改签
                original.setStatus("RESCHEDULED");
                original.setCancelReason("停诊迁移至" + alt.getName());
                appointmentMapper.updateById(original);

                // 创建新预约
                Appointment newAppt = new Appointment();
                newAppt.setAppointmentNo("MIG" + System.currentTimeMillis());
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

                // 占用新号源
                int updated = slotMapper.casBook(targetSlot.getId(), newAppt.getId(), targetSlot.getVersion());
                if (updated == 0) {
                    newAppt.setStatus("CANCELLED");
                    newAppt.setCancelReason("迁移失败-号源已被占");
                    appointmentMapper.updateById(newAppt);
                    continue;
                }

                auditService.log("MIGRATE_APPOINTMENT", "APPOINTMENT", newAppt.getId(),
                        String.format("{\"fromDoctor\":%d,\"toDoctor\":%d,\"fromAppt\":%d}",
                                original.getDoctorId(), alt.getId(), original.getId()));

                log.info("预约迁移成功: patient={}, from={} to={}, date={}",
                        original.getPatientId(), original.getDoctorId(), alt.getId(),
                        original.getSlotDate());

                return true;
            } catch (Exception e) {
                log.error("迁移到医生{}失败: {}", alt.getId(), e.getMessage());
            }
        }
        return false;
    }

    @Override
    public DoctorSuspension getById(Long id) {
        return suspensionMapper.selectById(id);
    }
}
