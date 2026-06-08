package com.clinic.appointment.service;

import com.clinic.appointment.domain.dto.BookRequest;
import com.clinic.appointment.domain.dto.CancelRequest;
import com.clinic.appointment.domain.dto.RescheduleRequest;
import com.clinic.appointment.domain.entity.Appointment;

import java.time.LocalDate;
import java.util.List;

/**
 * 预约核心服务 —— 预约、取消、改签、签到、过号
 */
public interface AppointmentService {

    /**
     * 预约挂号（带分布式锁 + 乐观锁双重保护）
     */
    Appointment book(BookRequest request);

    /**
     * 取消预约（释放号源 → 触发候补补位）
     */
    Appointment cancel(CancelRequest request);

    /**
     * 改签（释放旧号源 + 预约新号源，原子操作）
     */
    Appointment reschedule(RescheduleRequest request);

    /**
     * 签到
     */
    Appointment checkIn(Long appointmentId);

    /**
     * 过号（未按时签到）
     */
    Appointment markMissed(Long appointmentId);

    /**
     * 查询预约
     */
    Appointment getById(Long id);

    List<Appointment> getByPatient(Long patientId);

    List<Appointment> getByDoctorAndDate(Long doctorId, LocalDate date);

    /**
     * 批量取消某医生指定日期范围的预约（停诊迁移用）
     */
    List<Appointment> batchCancel(Long doctorId, LocalDate startDate, LocalDate endDate, String reason);
}
