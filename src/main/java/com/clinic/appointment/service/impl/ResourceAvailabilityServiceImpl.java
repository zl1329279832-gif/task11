package com.clinic.appointment.service.impl;

import com.clinic.appointment.domain.dto.EquipmentDeactivateRequest;
import com.clinic.appointment.domain.entity.*;
import com.clinic.appointment.domain.enums.ResourceStatus;
import com.clinic.appointment.domain.enums.ResourceType;
import com.clinic.appointment.mapper.*;
import com.clinic.appointment.service.AuditService;
import com.clinic.appointment.service.MultiResourceBookingService;
import com.clinic.appointment.service.RedisLockService;
import com.clinic.appointment.service.ResourceAvailabilityService;
import com.clinic.appointment.domain.dto.CancelRequest;
import com.clinic.appointment.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceAvailabilityServiceImpl implements ResourceAvailabilityService {

    private final ResourceAvailabilityMapper resourceAvailabilityMapper;
    private final ExamRoomMapper examRoomMapper;
    private final EquipmentMapper equipmentMapper;
    private final NursingStaffMapper nursingStaffMapper;
    private final AppointmentResourceMapper appointmentResourceMapper;
    private final AppointmentMapper appointmentMapper;
    private final ScheduleSlotMapper slotMapper;
    private final DoctorScheduleMapper scheduleMapper;
    private final RedisLockService lockService;
    private final AuditService auditService;

    @Autowired
    @Lazy
    private MultiResourceBookingService multiResourceBookingService;

    // 默认时段配置
    private static final Map<String, LocalTime[]> TIME_PERIODS = Map.of(
            "MORNING", new LocalTime[]{LocalTime.of(8, 0), LocalTime.of(12, 0)},
            "AFTERNOON", new LocalTime[]{LocalTime.of(13, 30), LocalTime.of(17, 0)}
    );
    private static final int SLOT_INTERVAL_MINUTES = 30;

    @Override
    @Transactional
    public int generateAvailability(Long departmentId, LocalDate date, String timePeriod) {
        LocalTime[] times = TIME_PERIODS.getOrDefault(timePeriod, TIME_PERIODS.get("MORNING"));
        LocalTime start = times[0];
        LocalTime end = times[1];

        int generated = 0;

        // 为诊室生成可用性窗口
        List<ExamRoom> rooms = examRoomMapper.findActiveByDepartment(departmentId);
        for (ExamRoom room : rooms) {
            generated += generateForResource(ResourceType.EXAM_ROOM.name(), room.getId(), date, start, end);
        }

        // 为设备生成可用性窗口
        List<Equipment> equipments = equipmentMapper.findActiveByDepartment(departmentId);
        for (Equipment equip : equipments) {
            generated += generateForResource(ResourceType.EQUIPMENT.name(), equip.getId(), date, start, end);
        }

        // 为护理人员生成可用性窗口
        List<NursingStaff> nurses = nursingStaffMapper.findActiveByDepartment(departmentId);
        for (NursingStaff nurse : nurses) {
            generated += generateForResource(ResourceType.NURSING_STAFF.name(), nurse.getId(), date, start, end);
        }

        log.info("资源可用性窗口生成: dept={}, date={}, period={}, count={}",
                departmentId, date, timePeriod, generated);
        return generated;
    }

    private int generateForResource(String resourceType, Long resourceId,
                                     LocalDate date, LocalTime start, LocalTime end) {
        int count = 0;
        LocalTime cursor = start;
        while (cursor.isBefore(end)) {
            LocalTime windowEnd = cursor.plusMinutes(SLOT_INTERVAL_MINUTES);
            if (windowEnd.isAfter(end)) {
                windowEnd = end;
            }

            ResourceAvailability avail = new ResourceAvailability();
            avail.setResourceType(resourceType);
            avail.setResourceId(resourceId);
            avail.setAvailDate(date);
            avail.setStartTime(cursor);
            avail.setEndTime(windowEnd);
            avail.setStatus(ResourceStatus.AVAILABLE.name());
            avail.setVersion(0);
            resourceAvailabilityMapper.insert(avail);
            count++;

            cursor = windowEnd;
        }
        return count;
    }

    @Override
    @Transactional
    public Map<String, Object> deactivateEquipment(EquipmentDeactivateRequest request) {
        Long equipmentId = request.getEquipmentId();
        LocalDate effectiveDate = request.getEffectiveDate();

        Equipment equipment = equipmentMapper.selectById(equipmentId);
        if (equipment == null) {
            throw BusinessException.of("EQUIPMENT_NOT_FOUND", "设备不存在");
        }

        String lockKey = "lock:equip_shutdown:" + equipmentId;
        return lockService.executeWithLock(lockKey, 10, 30, TimeUnit.SECONDS, () -> {
            // 1. 批量封锁未来可用窗口
            int blocked = resourceAvailabilityMapper.batchBlock(
                    ResourceType.EQUIPMENT.name(), equipmentId, effectiveDate);
            log.info("设备停用-封锁窗口: equipmentId={}, fromDate={}, blocked={}",
                    equipmentId, effectiveDate, blocked);

            // 2. 查找受影响的已预约窗口
            List<ResourceAvailability> bookedWindows = resourceAvailabilityMapper.findBookedFrom(
                    ResourceType.EQUIPMENT.name(), equipmentId, effectiveDate);

            int cancelledCount = 0;
            List<Long> cancelledAppointmentIds = new ArrayList<>();

            // 3. 逐一取消受影响的预约
            for (ResourceAvailability window : bookedWindows) {
                if (window.getAppointmentId() == null) continue;

                Long appointmentId = window.getAppointmentId();
                Appointment appt = appointmentMapper.selectById(appointmentId);
                if (appt == null) continue;

                try {
                    if ("EXAM".equals(appt.getAppointmentType())) {
                        // 联合预约：通过jointCancel释放所有资源
                        CancelRequest cancelReq = new CancelRequest();
                        cancelReq.setAppointmentId(appointmentId);
                        cancelReq.setReason("设备停用: " + (request.getReason() != null ? request.getReason() : ""));
                        multiResourceBookingService.jointCancel(cancelReq);
                    } else {
                        // 普通预约：直接取消
                        appt.setStatus("CANCELLED");
                        appt.setCancelReason("设备停用: " + (request.getReason() != null ? request.getReason() : ""));
                        appointmentMapper.updateById(appt);

                        ScheduleSlot slot = slotMapper.selectById(appt.getSlotId());
                        if (slot != null) {
                            slotMapper.casRelease(slot.getId(), slot.getVersion());
                            DoctorSchedule schedule = scheduleMapper.selectById(slot.getScheduleId());
                            if (schedule != null && schedule.getBookedSlots() > 0) {
                                schedule.setBookedSlots(schedule.getBookedSlots() - 1);
                                scheduleMapper.updateById(schedule);
                            }
                        }
                    }
                    cancelledAppointmentIds.add(appointmentId);
                    cancelledCount++;
                } catch (Exception e) {
                    log.error("设备停用取消预约失败: appointmentId={}, error={}", appointmentId, e.getMessage());
                }
            }

            // 4. 再次封锁因取消预约而释放回AVAILABLE的窗口
            int reblocked = resourceAvailabilityMapper.batchBlock(
                    ResourceType.EQUIPMENT.name(), equipmentId, effectiveDate);
            if (reblocked > 0) {
                log.info("设备停用-二次封锁释放窗口: equipmentId={}, reblocked={}", equipmentId, reblocked);
                blocked += reblocked;
            }

            // 5. 更新设备状态
            equipment.setStatus("INACTIVE");
            equipmentMapper.updateById(equipment);

            auditService.log("EQUIPMENT_DEACTIVATE", "EQUIPMENT", equipmentId,
                    String.format("{\"effectiveDate\":\"%s\",\"blocked\":%d,\"cancelled\":%d}",
                            effectiveDate, blocked, cancelledCount));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("equipmentId", equipmentId);
            result.put("equipmentName", equipment.getName());
            result.put("effectiveDate", effectiveDate);
            result.put("blockedWindows", blocked);
            result.put("cancelledAppointments", cancelledCount);
            result.put("cancelledAppointmentIds", cancelledAppointmentIds);

            log.info("设备停用完成: {}", result);
            return result;
        });
    }
}
