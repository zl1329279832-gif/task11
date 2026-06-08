package com.clinic.appointment.service;

import com.clinic.appointment.domain.dto.WaitlistRequest;
import com.clinic.appointment.domain.entity.Waitlist;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 候补队列服务 —— 加入候补、自动补位、过期清理
 */
public interface WaitlistService {

    /**
     * 加入候补队列
     */
    Waitlist join(WaitlistRequest request);

    /**
     * 取消候补
     */
    Waitlist cancel(Long waitlistId);

    /**
     * 触发候补补位（号源释放时调用）
     * 核心逻辑：找到该医生该日期的候补队列，将第一个WAITING的患者自动预约到释放的号源
     */
    void triggerBackfill(Long doctorId, LocalDate slotDate, LocalTime slotTime);

    /**
     * 定时扫描：处理过期候补
     */
    int expireWaitlists();

    /**
     * 查询候补队列
     */
    List<Waitlist> getByPatient(Long patientId);

    List<Waitlist> getWaitingByDoctorAndDate(Long doctorId, LocalDate date);
}
