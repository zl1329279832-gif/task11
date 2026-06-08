package com.clinic.appointment.service.impl;

import com.clinic.appointment.domain.dto.SuspendRequest;
import com.clinic.appointment.domain.entity.*;
import com.clinic.appointment.domain.enums.SlotStatus;
import com.clinic.appointment.mapper.*;
import com.clinic.appointment.service.AppointmentService;
import com.clinic.appointment.service.AuditService;
import com.clinic.appointment.service.DoctorSuspensionService;
import com.clinic.appointment.service.RedisLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

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

    private final AppointmentService appointmentService;

    @Override
    @Transactional
    public Map<String, Object> suspend(SuspendRequest request) {
        Long doctorId = request.getDoctorId();
        LocalDate startDate = request.getStartDate();
        LocalDate endDate = request.getEndDate();
        String migrateType = request.getMigrateType();

        // 1. 锁保护
        String lockKey = "lock:suspend:" + doctorId;
        return lockService.executeWithLock(lockKey, () -> {
            Doctor doctor = doctorMapper.selectById(doctorId);
            if (doctor == null) {
                throw new com.clinic.appointment.exception.BusinessException(
                        "DOCTOR_NOT_FOUND", "医生不存在");
            }

            // 2. 创建停诊记录
            DoctorSuspension suspension = new DoctorSuspension();
            suspension.setDoctorId(doctorId);
            suspension.setStartDate(startDate);
            suspension.setEndDate(endDate);
            suspension.setReason(request.getReason() != null ? request.getReason() : "");
            suspension.setMigrateType(migrateType);
            suspension.setStatus("PROCESSING");
            suspensionMapper.insert(suspension);

            int cancelledCount = 0;
            int migratedCount = 0;
            int failedCount = 0;
            List<String> details = new ArrayList<>();

            // 3. 查找受影响的预约
            List<Appointment> affected = appointmentMapper
                    .findActiveByDoctorFromDate(doctorId, startDate);
            affected = affected.stream()
                    .filter(a -> !a.getSlotDate().isAfter(endDate))
                    .toList();

            log.info("停诊处理: doctor={}, range={}~{}, affected={}",
                    doctorId, startDate, endDate, affected.size());

            if ("CANCEL".equals(migrateType)) {
                // ── 取消模式 ──
                String reason = "医生停诊: " + suspension.getReason();
                List<Appointment> cancelled = appointmentService
                        .batchCancel(doctorId, startDate, endDate, reason);
                cancelledCount = cancelled.size();
                details.add(String.format("取消%d个预约", cancelledCount));

            } else if ("MIGRATE".equals(migrateType)) {
                // ── 迁移模式 ──
                // 查找同科室其他在职医生
                List<Doctor> alternatives = doctorMapper
                        .findActiveByDepartment(doctor.getDepartmentId());
                alternatives = alternatives.stream()
                        .filter(d -> !d.getId().equals(doctorId))
                        .toList();

                if (alternatives.isEmpty()) {
                    // 没有可迁移的医生，降级为取消
                    log.warn("无可迁移医生，降级为取消: doctor={}", doctorId);
                    String reason = "医生停诊(无可迁移医生): " + suspension.getReason();
                    List<Appointment> cancelled = appointmentService
                            .batchCancel(doctorId, startDate, endDate, reason);
                    cancelledCount = cancelled.size();
                    details.add("无可迁移医生，降级取消" + cancelledCount + "个预约");
                } else {
                    // 尝试迁移每个预约
                    for (Appointment appt : affected) {
                        try {
                            boolean migrated = migrateAppointment(appt, alternatives);
                            if (migrated) {
                                migratedCount++;
                            } else {
                                // 迁移失败，取消
                                cancelSingleAppointment(appt,
                                        "医生停诊且无法迁移: " + suspension.getReason());
                                cancelledCount++;
                            }
                        } catch (Exception e) {
                            log.error("迁移预约失败: appointmentId={}, error={}",
                                    appt.getId(), e.getMessage());
                            cancelSingleAppointment(appt,
                                    "医生停诊迁移失败: " + e.getMessage());
                            cancelledCount++;
                            failedCount++;
                        }
                    }
                    details.add(String.format("迁移%d个, 取消%d个, 失败%d个",
                            migratedCount, cancelledCount, failedCount));
                }
            }

            // 4. 批量释放号源 & 更新排班状态
            slotMapper.batchRelease(doctorId, startDate, endDate);
            scheduleMapper.batchUpdateStatus(doctorId, startDate, "SUSPENDED");

            // 5. 更新停诊状态
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
    }

    /**
     * 尝试将预约迁移到同科室其他医生
     */
    private boolean migrateAppointment(Appointment original, List<Doctor> alternatives) {
        for (Doctor alt : alternatives) {
            // 查找替代医生同日的可用号源
            List<ScheduleSlot> altSlots = slotMapper.findAvailable(alt.getId(), original.getSlotDate());
            if (altSlots.isEmpty()) continue;

            // 尝试预约第一个可用号源
            ScheduleSlot targetSlot = altSlots.get(0);
            try {
                // 标记原预约为已改签
                original.setStatus("RESCHEDULED");
                original.setCancelReason("停诊迁移至" + alt.getName());
                appointmentMapper.updateById(original);

                // 释放原号源
                ScheduleSlot oldSlot = slotMapper.selectById(original.getSlotId());
                if (oldSlot != null) {
                    slotMapper.casRelease(oldSlot.getId(), oldSlot.getVersion());
                }

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
                    // 号源已被占，尝试下一个医生
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

    private void cancelSingleAppointment(Appointment appt, String reason) {
        appt.setStatus("CANCELLED");
        appt.setCancelReason(reason);
        appointmentMapper.updateById(appt);

        ScheduleSlot slot = slotMapper.selectById(appt.getSlotId());
        if (slot != null) {
            slotMapper.casRelease(slot.getId(), slot.getVersion());
        }
    }

    @Override
    public DoctorSuspension getById(Long id) {
        return suspensionMapper.selectById(id);
    }
}
