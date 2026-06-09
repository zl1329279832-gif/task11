package com.clinic.appointment.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.clinic.appointment.domain.dto.BookRequest;
import com.clinic.appointment.domain.dto.JointBookRequest;
import com.clinic.appointment.domain.dto.JointBookingResult;
import com.clinic.appointment.domain.dto.WaitlistRequest;
import com.clinic.appointment.domain.entity.Appointment;
import com.clinic.appointment.domain.entity.ScheduleSlot;
import com.clinic.appointment.domain.entity.Waitlist;
import com.clinic.appointment.domain.enums.SlotStatus;
import com.clinic.appointment.exception.BusinessException;
import com.clinic.appointment.mapper.ScheduleSlotMapper;
import com.clinic.appointment.mapper.WaitlistMapper;
import com.clinic.appointment.service.AppointmentService;
import com.clinic.appointment.service.AuditService;
import com.clinic.appointment.service.MultiResourceBookingService;
import com.clinic.appointment.service.RedisLockService;
import com.clinic.appointment.service.WaitlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WaitlistServiceImpl implements WaitlistService {

    private final WaitlistMapper waitlistMapper;
    private final ScheduleSlotMapper slotMapper;
    private final RedisLockService lockService;
    private final AuditService auditService;

    @Autowired
    @Lazy
    private AppointmentService appointmentService;

    @Autowired
    @Lazy
    private MultiResourceBookingService multiResourceBookingService;

    private static final int MAX_WAITLIST_SIZE = 20;

    @Override
    @Transactional
    public Waitlist join(WaitlistRequest request) {
        // 检查候补队列是否已满
        LambdaQueryWrapper<Waitlist> qw = new LambdaQueryWrapper<>();
        qw.eq(Waitlist::getDoctorId, request.getDoctorId())
          .eq(Waitlist::getTargetDate, request.getTargetDate())
          .eq(Waitlist::getStatus, "WAITING");
        long count = waitlistMapper.selectCount(qw);
        if (count >= MAX_WAITLIST_SIZE) {
            throw BusinessException.waitlistFull();
        }

        // 检查是否重复加入
        LambdaQueryWrapper<Waitlist> dupQw = new LambdaQueryWrapper<>();
        dupQw.eq(Waitlist::getPatientId, request.getPatientId())
             .eq(Waitlist::getDoctorId, request.getDoctorId())
             .eq(Waitlist::getTargetDate, request.getTargetDate())
             .eq(Waitlist::getStatus, "WAITING");
        if (waitlistMapper.selectCount(dupQw) > 0) {
            throw BusinessException.of("DUPLICATE_WAITLIST", "已在候补队列中");
        }

        Waitlist waitlist = new Waitlist();
        waitlist.setPatientId(request.getPatientId());
        waitlist.setPatientName(request.getPatientName() != null ? request.getPatientName() : "");
        waitlist.setDoctorId(request.getDoctorId());
        waitlist.setDepartmentId(request.getDepartmentId());
        waitlist.setTargetDate(request.getTargetDate());
        waitlist.setTimePeriod(request.getTimePeriod());
        waitlist.setStatus("WAITING");
        waitlist.setAppointmentType(request.getAppointmentType() != null ? request.getAppointmentType() : "NORMAL");
        waitlist.setExamType(request.getExamType());
        // 候补有效期：目标日期的前一天晚上23:59过期
        waitlist.setExpireTime(request.getTargetDate().minusDays(1).atTime(23, 59, 59));
        waitlistMapper.insert(waitlist);

        // 使用自增ID作为优先级，保证严格FIFO（AUTO_INCREMENT保证唯一递增）
        waitlist.setPriority(waitlist.getId().intValue());
        waitlistMapper.updateById(waitlist);

        auditService.log("JOIN_WAITLIST", "WAITLIST", waitlist.getId(),
                String.format("{\"patientId\":%d,\"doctorId\":%d,\"date\":\"%s\"}",
                        request.getPatientId(), request.getDoctorId(), request.getTargetDate()));

        log.info("加入候补: patient={}, doctor={}, date={}, priority={}",
                request.getPatientId(), request.getDoctorId(), request.getTargetDate(), waitlist.getPriority());

        return waitlist;
    }

    @Override
    @Transactional
    public Waitlist cancel(Long waitlistId) {
        Waitlist waitlist = waitlistMapper.selectById(waitlistId);
        if (waitlist == null) {
            throw BusinessException.of("WAITLIST_NOT_FOUND", "候补记录不存在");
        }
        if (!"WAITING".equals(waitlist.getStatus())) {
            throw BusinessException.of("WAITLIST_NOT_WAITING", "候补状态不允许取消");
        }

        waitlist.setStatus("CANCELLED");
        waitlistMapper.updateById(waitlist);

        auditService.log("CANCEL_WAITLIST", "WAITLIST", waitlistId, null);
        return waitlist;
    }

    @Override
    @Transactional
    public void triggerBackfill(Long doctorId, LocalDate slotDate, LocalTime slotTime) {
        String lockKey = "lock:backfill:" + doctorId + ":" + slotDate;
        lockService.executeWithLock(lockKey, () -> {
            // 1. 查找释放后可用的号源
            List<ScheduleSlot> available = slotMapper.findAvailable(doctorId, slotDate);
            if (available.isEmpty()) {
                log.debug("无可补位号源: doctor={}, date={}", doctorId, slotDate);
                return null;
            }

            // 2. 获取候补队列（按优先级即ID排序，严格FIFO）
            List<Waitlist> waitingList = waitlistMapper.findWaiting(doctorId, slotDate);
            if (waitingList.isEmpty()) {
                log.debug("无候补患者: doctor={}, date={}", doctorId, slotDate);
                return null;
            }

            // 3. 严格按顺序逐一补位
            int filled = 0;
            for (Waitlist waiter : waitingList) {
                if (available.size() <= filled) break;

                ScheduleSlot targetSlot = available.get(filled);

                try {
                    if ("EXAM".equals(waiter.getAppointmentType())) {
                        // 联合预约候补补位：需要同时锁定多资源
                        JointBookRequest jointReq = new JointBookRequest();
                        jointReq.setPatientId(waiter.getPatientId());
                        jointReq.setPatientName(waiter.getPatientName());
                        jointReq.setSlotId(targetSlot.getId());
                        jointReq.setExamType(waiter.getExamType());

                        JointBookingResult result = multiResourceBookingService.jointBook(jointReq);

                        waiter.setStatus("FULFILLED");
                        waiter.setAppointmentId(result.getAppointment().getId());
                        waitlistMapper.updateById(waiter);

                        auditService.log("WAITLIST_BACKFILL_JOINT", "APPOINTMENT",
                                result.getAppointment().getId(),
                                String.format("{\"waitlistId\":%d,\"patientId\":%d,\"slotId\":%d}",
                                        waiter.getId(), waiter.getPatientId(), targetSlot.getId()));

                        log.info("联合候补补位成功: waitlistId={}, patient={}, appointment={}",
                                waiter.getId(), waiter.getPatientId(),
                                result.getAppointment().getAppointmentNo());

                        filled++;
                    } else {
                        // 普通预约候补补位
                        BookRequest bookReq = new BookRequest();
                        bookReq.setPatientId(waiter.getPatientId());
                        bookReq.setPatientName(waiter.getPatientName());
                        bookReq.setSlotId(targetSlot.getId());

                        Appointment appointment = appointmentService.book(bookReq);

                        waiter.setStatus("FULFILLED");
                        waiter.setAppointmentId(appointment.getId());
                        waitlistMapper.updateById(waiter);

                        auditService.log("WAITLIST_BACKFILL", "APPOINTMENT", appointment.getId(),
                                String.format("{\"waitlistId\":%d,\"patientId\":%d,\"slotId\":%d}",
                                        waiter.getId(), waiter.getPatientId(), targetSlot.getId()));

                        log.info("候补补位成功: waitlistId={}, patient={}, appointment={}, slot={}",
                                waiter.getId(), waiter.getPatientId(),
                                appointment.getAppointmentNo(), targetSlot.getId());

                        filled++;
                    }
                } catch (Exception e) {
                    log.error("候补补位失败，停止当前轮次以维护FIFO顺序: waitlistId={}, patient={}, error={}",
                            waiter.getId(), waiter.getPatientId(), e.getMessage());
                    break;
                }
            }

            log.info("候补补位完成: doctor={}, date={}, filled={}", doctorId, slotDate, filled);
            return null;
        });
    }

    @Override
    @Transactional
    public int expireWaitlists() {
        LambdaQueryWrapper<Waitlist> qw = new LambdaQueryWrapper<>();
        qw.eq(Waitlist::getStatus, "WAITING")
          .le(Waitlist::getExpireTime, LocalDateTime.now());
        List<Waitlist> expired = waitlistMapper.selectList(qw);

        for (Waitlist w : expired) {
            w.setStatus("EXPIRED");
            waitlistMapper.updateById(w);
        }

        if (!expired.isEmpty()) {
            log.info("候补过期处理: count={}", expired.size());
        }
        return expired.size();
    }

    @Override
    public List<Waitlist> getByPatient(Long patientId) {
        LambdaQueryWrapper<Waitlist> qw = new LambdaQueryWrapper<>();
        qw.eq(Waitlist::getPatientId, patientId).orderByDesc(Waitlist::getCreateTime);
        return waitlistMapper.selectList(qw);
    }

    @Override
    public List<Waitlist> getWaitingByDoctorAndDate(Long doctorId, LocalDate date) {
        return waitlistMapper.findWaiting(doctorId, date);
    }
}
