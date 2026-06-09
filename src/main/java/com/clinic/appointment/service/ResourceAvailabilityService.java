package com.clinic.appointment.service;

import com.clinic.appointment.domain.dto.EquipmentDeactivateRequest;

import java.time.LocalDate;
import java.util.Map;

public interface ResourceAvailabilityService {

    /**
     * 为科室所有活跃资源生成指定日期和时段的可用性窗口
     */
    int generateAvailability(Long departmentId, LocalDate date, String timePeriod);

    /**
     * 设备停用：封锁未来可用窗口，取消受影响预约
     */
    Map<String, Object> deactivateEquipment(EquipmentDeactivateRequest request);
}
