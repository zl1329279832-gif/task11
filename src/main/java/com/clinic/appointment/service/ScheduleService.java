package com.clinic.appointment.service;

import com.clinic.appointment.domain.dto.ExtraSlotRequest;
import com.clinic.appointment.domain.dto.ScheduleGenerateRequest;
import com.clinic.appointment.domain.entity.DoctorSchedule;
import com.clinic.appointment.domain.entity.ScheduleSlot;

import java.time.LocalDate;
import java.util.List;

/**
 * 排班服务 —— 模板生成、节假日停诊、临时加号
 */
public interface ScheduleService {

    /**
     * 根据排班模板生成指定日期范围的日排班和号源
     */
    List<DoctorSchedule> generateSchedule(ScheduleGenerateRequest request);

    /**
     * 根据节假日停诊配置，批量将受影响排标记为HOLIDAY
     */
    int applyHolidaySuspension(LocalDate date, Long departmentId);

    /**
     * 临时加号
     */
    ScheduleSlot addExtraSlot(ExtraSlotRequest request);

    /**
     * 查询某医生某日期的所有号源
     */
    List<ScheduleSlot> getSlotsByDoctorAndDate(Long doctorId, LocalDate date);

    /**
     * 查询某医生日期范围的排班
     */
    List<DoctorSchedule> getSchedules(Long doctorId, LocalDate start, LocalDate end);
}
