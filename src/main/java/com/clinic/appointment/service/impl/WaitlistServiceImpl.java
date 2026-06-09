package com.clinic.appointment.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.clinic.appointment.domain.dto.BookRequest;
import com.clinic.appointment.domain.dto.WaitlistRequest;
import com.clinic.appointment.domain.entity.Appointment;
import com.clinic.appointment.domain.entity.ScheduleSlot;
import com.clinic.appointment.domain.entity.Waitlist;
import com.clinic.appointment.exception.BusinessException;
import com.clinic.appointment.mapper.ScheduleSlotMapper;
import com.clinic.appointment.mapper.WaitlistMapper;
import com.clinic.appointment.service.AppointmentService;
import com.clinic.appointment.service.AuditService;
import com.clinic.appointment.service.RedisLockService;
import com.clinic.appointment.service.WaitlistService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 候补服务实现
 *
 * 并发安全设计：
 * - triggerBackfill 使用 backfill 锁保证同一医生同一天只有一个补位操作
 * - 每次补位使用独立事务（TransactionTemplate），一个失败不影响其他
 * - 每次循环重新查询可用号源，避免卡在同一个不可用号源上
 * - 使用 findAvailableWithScheduleCheck 排除已停诊排班的号源
 * - 候补队列严格按 priority ASC, create_time ASC 排序补位
 */
@Slf4j
@Service
public class WaitlistServiceImpl implements WaitlistService {

    private final WaitlistMapper waitlistMapper;
    private final ScheduleSlotMapper slotMapper;
    private final RedisLockService lockService;
    private final AuditService auditService;
    private final TransactionTemplate txTemplate;

    @Autowired
    @Lazy
    private AppointmentService appointmentService;

    private static final int MAX_WAITLIST_SIZE = 20;

    public WaitlistServiceImpl(WaitlistMapper waitlistMapper,
                               ScheduleSlotMapper slotMapper,
                               RedisLockService lockService,
                               AuditService auditService,
                               PlatformTransactionManager txManager) {
        this.waitlistMapper = waitlistMapper;
        this.slotMapper = slotMapper;
        this.lockService = lockService;
        this.auditService = auditService;
        this.txTemplate = new TransactionTemplate(txManager);
    }

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
        waitlist.setPriority((int) count);
        waitlist.setStatus("WAITING");
        waitlist.setExpireTime(request.getTargetDate().minusDays(1).atTime(23, 59, 59));
        waitlistMapper.insert(waitlist);

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

    /**
     * 触发候补补位
     *
     * 关键设计：
     * 1. 不在外层开事务，每次补位操作（book + 更新候补状态）使用独立事务
     *    → 一个候补失败不会导致其他已成功的候补回滚
     * 2. 每次循环重新查询可用号源（含排班状态校验）
     *    → 避免book失败后卡在同一个不可用号源上
     * 3. 严格按 findWaiting 返回顺序（priority ASC, create_time ASC）补位
     *    → 保证先到先得
     * 4. backfill 锁保证同一医生同一天同时只有一个补位操作
     *    → 防止定时任务和取消触发的补位并发执行导致重复补位
     */
    @Override
    public void triggerBackfill(Long doctorId, LocalDate slotDate, LocalTime slotTime) {
        String lockKey = "lock:backfill:" + doctorId + ":" + slotDate;
        lockService.executeWithLock(lockKey, () -> {
            // 获取候补队列（严格排序：priority ASC, create_time ASC）
            List<Waitlist> waitingList = waitlistMapper.findWaiting(doctorId, slotDate);
            if (waitingList.isEmpty()) {
                log.debug("无候补患者: doctor={}, date={}", doctorId, slotDate);
                return null;
            }

            int filled = 0;
            for (Waitlist waiter : waitingList) {
                // 每次循环重新查询可用号源（含排班状态校验，排除已停诊）
                List<ScheduleSlot> available = slotMapper.findAvailableWithScheduleCheck(doctorId, slotDate);
                if (available.isEmpty()) {
                    log.debug("无可补位号源: doctor={}, date={}", doctorId, slotDate);
                    break;
                }

                // 重新检查候补状态（可能被并发cancel或expire）
                Waitlist freshWaiter = waitlistMapper.selectById(waiter.getId());
                if (freshWaiter == null || !"WAITING".equals(freshWaiter.getStatus())) {
                    continue;
                }

                ScheduleSlot targetSlot = available.get(0);

                try {
                    // book() 有自己的分布式锁和事务，独立完成
                    BookRequest bookReq = new BookRequest();
                    bookReq.setPatientId(freshWaiter.getPatientId());
                    bookReq.setPatientName(freshWaiter.getPatientName());
                    bookReq.setSlotId(targetSlot.getId());

                    Appointment appointment = appointmentService.book(bookReq);

                    // book成功后，独立事务更新候补状态为FULFILLED
                    txTemplate.execute(s -> {
                        Waitlist latestWaiter = waitlistMapper.selectById(freshWaiter.getId());
                        if (latestWaiter != null && "WAITING".equals(latestWaiter.getStatus())) {
                            latestWaiter.setStatus("FULFILLED");
                            latestWaiter.setAppointmentId(appointment.getId());
                            waitlistMapper.updateById(latestWaiter);
                        }
                        return null;
                    });

                    auditService.log("WAITLIST_BACKFILL", "APPOINTMENT", appointment.getId(),
                            String.format("{\"waitlistId\":%d,\"patientId\":%d,\"slotId\":%d}",
                                    freshWaiter.getId(), freshWaiter.getPatientId(), targetSlot.getId()));

                    log.info("候补补位成功: waitlistId={}, patient={}, appointment={}, slot={}",
                            freshWaiter.getId(), freshWaiter.getPatientId(),
                            appointment.getAppointmentNo(), targetSlot.getId());

                    filled++;
                } catch (Exception e) {
                    log.error("候补补位失败: waitlistId={}, patient={}, slot={}, error={}",
                            freshWaiter.getId(), freshWaiter.getPatientId(),
                            targetSlot.getId(), e.getMessage());
                    // 继续下一个候补患者（号源会在下次循环重新查询）
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
