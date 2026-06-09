package com.clinic.appointment.service.impl;

import com.clinic.appointment.domain.dto.BookRequest;
import com.clinic.appointment.domain.dto.ResourceAllocation;
import com.clinic.appointment.domain.entity.AppointmentResource;
import com.clinic.appointment.domain.entity.ExamType;
import com.clinic.appointment.domain.entity.ResourceSlot;
import com.clinic.appointment.exception.BusinessException;
import com.clinic.appointment.mapper.AppointmentMapper;
import com.clinic.appointment.mapper.AppointmentResourceMapper;
import com.clinic.appointment.mapper.ExamTypeMapper;
import com.clinic.appointment.mapper.ResourceSlotMapper;
import com.clinic.appointment.service.ResourceScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceScheduleServiceImpl implements ResourceScheduleService {

    private final ResourceSlotMapper resourceSlotMapper;
    private final ExamTypeMapper examTypeMapper;
    private final AppointmentResourceMapper appointmentResourceMapper;
    private final AppointmentMapper appointmentMapper;

    @Override
    public ResourceAllocation resolveResources(ExamType examType, LocalDate date, LocalTime time,
                                                BookRequest request) {
        ResourceAllocation allocation = new ResourceAllocation();

        // 诊室资源
        if (examType.getNeedRoom() != null && examType.getNeedRoom() == 1) {
            ResourceSlot roomSlot = resolveOneResource(
                    request.getRoomResourceSlotId(),
                    examType.getRoomCode(),
                    "ROOM", date, time);
            if (roomSlot == null) {
                throw BusinessException.roomUnavailable();
            }
            allocation.setRoomSlot(roomSlot);
        }

        // 设备资源
        if (examType.getNeedEquipment() != null && examType.getNeedEquipment() == 1) {
            ResourceSlot equipmentSlot = resolveOneResource(
                    request.getEquipmentResourceSlotId(),
                    examType.getEquipmentCode(),
                    "EQUIPMENT", date, time);
            if (equipmentSlot == null) {
                throw BusinessException.equipmentUnavailable();
            }
            allocation.setEquipmentSlot(equipmentSlot);
        }

        // 护理资源
        if (examType.getNeedNursing() != null && examType.getNeedNursing() == 1) {
            ResourceSlot nursingSlot = resolveOneResource(
                    request.getNursingResourceSlotId(),
                    null,
                    "NURSING", date, time);
            if (nursingSlot == null) {
                throw BusinessException.nursingUnavailable();
            }
            allocation.setNursingSlot(nursingSlot);
        }

        return allocation;
    }

    @Override
    public List<AppointmentResource> bookResources(Long appointmentId, ResourceAllocation allocation) {
        List<AppointmentResource> records = new ArrayList<>();

        if (allocation.getRoomSlot() != null) {
            records.add(casBookOneResource(appointmentId, allocation.getRoomSlot(), "ROOM"));
        }
        if (allocation.getEquipmentSlot() != null) {
            records.add(casBookOneResource(appointmentId, allocation.getEquipmentSlot(), "EQUIPMENT"));
        }
        if (allocation.getNursingSlot() != null) {
            records.add(casBookOneResource(appointmentId, allocation.getNursingSlot(), "NURSING"));
        }

        return records;
    }

    @Override
    public void releaseResources(Long appointmentId) {
        List<AppointmentResource> resources = appointmentResourceMapper.findByAppointment(appointmentId);
        for (AppointmentResource ar : resources) {
            ResourceSlot slot = resourceSlotMapper.selectById(ar.getResourceSlotId());
            if (slot != null && slot.getBookedCount() > 0) {
                int released = resourceSlotMapper.casRelease(slot.getId(), slot.getVersion());
                if (released == 0) {
                    log.warn("资源时段释放CAS失败，可能已被其他操作修改: resourceSlotId={}", slot.getId());
                }
            }
        }
        appointmentResourceMapper.releaseByAppointment(appointmentId);
        log.info("释放预约资源: appointmentId={}, resourceCount={}", appointmentId, resources.size());
    }

    @Override
    public boolean checkResourceAvailability(String examTypeCode, LocalDate date, LocalTime time) {
        ExamType examType = examTypeMapper.findByCode(examTypeCode);
        if (examType == null) {
            return false;
        }

        if (examType.getNeedRoom() != null && examType.getNeedRoom() == 1) {
            if (findAvailableSlot(examType.getRoomCode(), "ROOM", date, time) == null) {
                return false;
            }
        }
        if (examType.getNeedEquipment() != null && examType.getNeedEquipment() == 1) {
            if (findAvailableSlot(examType.getEquipmentCode(), "EQUIPMENT", date, time) == null) {
                return false;
            }
        }
        if (examType.getNeedNursing() != null && examType.getNeedNursing() == 1) {
            if (findAvailableSlot(null, "NURSING", date, time) == null) {
                return false;
            }
        }

        return true;
    }

    @Override
    public void validatePatientLimits(Long patientId, String examTypeCode, LocalDate date) {
        ExamType examType = examTypeMapper.findByCode(examTypeCode);
        if (examType == null) {
            throw BusinessException.examTypeNotFound();
        }
        if (examType.getPatientDailyLimit() != null) {
            int count = appointmentMapper.countPatientExamOnDate(patientId, date, examTypeCode);
            if (count >= examType.getPatientDailyLimit()) {
                throw BusinessException.patientExamLimitExceeded();
            }
        }
    }

    /**
     * 解析单个资源时段：优先使用显式指定的ID，否则按编码或类型自动匹配
     */
    private ResourceSlot resolveOneResource(Long explicitSlotId, String resourceCode,
                                             String resourceType, LocalDate date, LocalTime time) {
        if (explicitSlotId != null) {
            ResourceSlot slot = resourceSlotMapper.selectById(explicitSlotId);
            if (slot != null && "AVAILABLE".equals(slot.getStatus())
                    && slot.getBookedCount() < slot.getCapacity()) {
                return slot;
            }
            return null;
        }

        if (resourceCode != null) {
            List<ResourceSlot> candidates = resourceSlotMapper.findAvailableByCode(
                    resourceCode, date, time);
            return candidates.isEmpty() ? null : candidates.get(0);
        }

        List<ResourceSlot> candidates = resourceSlotMapper.findAvailableByType(
                resourceType, date, time);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * 查找可用资源时段（用于可用性检查）
     */
    private ResourceSlot findAvailableSlot(String resourceCode, String resourceType,
                                            LocalDate date, LocalTime time) {
        if (resourceCode != null) {
            List<ResourceSlot> slots = resourceSlotMapper.findAvailableByCode(resourceCode, date, time);
            return slots.isEmpty() ? null : slots.get(0);
        }
        List<ResourceSlot> slots = resourceSlotMapper.findAvailableByType(resourceType, date, time);
        return slots.isEmpty() ? null : slots.get(0);
    }

    /**
     * CAS预订单个资源时段并创建关联记录
     */
    private AppointmentResource casBookOneResource(Long appointmentId, ResourceSlot slot,
                                                    String resourceType) {
        ResourceSlot fresh = resourceSlotMapper.selectById(slot.getId());
        if (fresh == null || !"AVAILABLE".equals(fresh.getStatus())) {
            throw BusinessException.resourceCasFailed(resourceType);
        }

        int updated = resourceSlotMapper.casBook(fresh.getId(), fresh.getVersion());
        if (updated == 0) {
            throw BusinessException.resourceCasFailed(resourceType);
        }

        AppointmentResource ar = new AppointmentResource();
        ar.setAppointmentId(appointmentId);
        ar.setResourceSlotId(fresh.getId());
        ar.setResourceId(fresh.getResourceId());
        ar.setResourceType(resourceType);
        ar.setStatus("BOOKED");
        appointmentResourceMapper.insert(ar);

        log.info("资源预订成功: appointmentId={}, resourceType={}, resourceSlotId={}",
                appointmentId, resourceType, fresh.getId());
        return ar;
    }
}
