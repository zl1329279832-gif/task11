package com.clinic.appointment.service;

import com.clinic.appointment.domain.dto.BookRequest;
import com.clinic.appointment.domain.dto.ResourceAllocation;
import com.clinic.appointment.domain.entity.AppointmentResource;
import com.clinic.appointment.domain.entity.ExamType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public interface ResourceScheduleService {

    /**
     * 解析联合预约所需的资源时段（只读操作，锁外调用）
     * 根据检查类型配置，查找可用的诊室/设备/护理资源时段
     */
    ResourceAllocation resolveResources(ExamType examType, LocalDate date, LocalTime time,
                                         BookRequest request);

    /**
     * CAS预订所有资源时段，创建appointment_resource记录
     * 必须在分布式锁内调用。任一CAS失败抛异常，由调用方事务回滚
     */
    List<AppointmentResource> bookResources(Long appointmentId, ResourceAllocation allocation);

    /**
     * CAS释放预约关联的所有资源时段
     * 必须在分布式锁内调用
     */
    void releaseResources(Long appointmentId);

    /**
     * 检查指定检查类型在给定日期时间的资源是否可用
     * 用于候补补位前的校验
     */
    boolean checkResourceAvailability(String examTypeCode, LocalDate date, LocalTime time);

    /**
     * 校验患者每日检查次数限制
     */
    void validatePatientLimits(Long patientId, String examTypeCode, LocalDate date);
}
