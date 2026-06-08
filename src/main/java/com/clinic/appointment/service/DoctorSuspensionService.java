package com.clinic.appointment.service;

import com.clinic.appointment.domain.dto.SuspendRequest;
import com.clinic.appointment.domain.entity.DoctorSuspension;

import java.util.Map;

/**
 * 医生停诊服务 —— 停诊发布、批量迁移/取消受影响预约
 */
public interface DoctorSuspensionService {

    /**
     * 创建停诊并处理受影响预约
     * - CANCEL模式：批量取消所有受影响预约
     * - MIGRATE模式：尝试将预约迁移到同科室其他医生
     *
     * @return 处理结果统计
     */
    Map<String, Object> suspend(SuspendRequest request);

    /**
     * 查询停诊记录
     */
    DoctorSuspension getById(Long id);
}
