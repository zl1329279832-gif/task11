package com.clinic.appointment.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.clinic.appointment.domain.dto.ExtraSlotRequest;
import com.clinic.appointment.domain.dto.ScheduleGenerateRequest;
import com.clinic.appointment.domain.entity.*;
import com.clinic.appointment.domain.enums.SlotStatus;
import com.clinic.appointment.exception.BusinessException;
import com.clinic.appointment.mapper.*;
import com.clinic.appointment.service.AuditService;
import com.clinic.appointment.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleServiceImpl implements ScheduleService {

    private final DoctorScheduleMapper scheduleMapper;
    private final ScheduleSlotMapper slotMapper;
    private final ScheduleTemplateMapper templateMapper;
    private final HolidayMapper holidayMapper;
    private final DoctorMapper doctorMapper;
    private final AuditService auditService;

    @Override
    @Transactional
    public List<DoctorSchedule> generateSchedule(ScheduleGenerateRequest request) {
        Long doctorId = request.getDoctorId();
        LocalDate start = request.getStartDate();
        LocalDate end = request.getEndDate();

        Doctor doctor = doctorMapper.selectById(doctorId);
        if (doctor == null) {
            throw BusinessException.of("DOCTOR_NOT_FOUND", "医生不存在");
        }

        // 查询该医生的所有排班模板
        LambdaQueryWrapper<ScheduleTemplate> tw = new LambdaQueryWrapper<>();
        tw.eq(ScheduleTemplate::getDoctorId, doctorId).eq(ScheduleTemplate::getStatus, 1);
        List<ScheduleTemplate> templates = templateMapper.selectList(tw);

        // 查询已有排班（避免重复生成）
        List<DoctorSchedule> existing = scheduleMapper.findByDoctorAndDateRange(doctorId, start, end);

        // 查询节假日
        LambdaQueryWrapper<Holiday> hw = new LambdaQueryWrapper<>();
        hw.ge(Holiday::getHolidayDate, start).le(Holiday::getHolidayDate, end);
        hw.and(w -> w.eq(Holiday::getScope, "ALL")
                .or().eq(Holiday::getDepartmentId, doctor.getDepartmentId()));
        List<Holiday> holidays = holidayMapper.selectList(hw);
        List<LocalDate> holidayDates = holidays.stream()
                .map(Holiday::getHolidayDate).toList();

        List<DoctorSchedule> result = new ArrayList<>();

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            // 映射日期到 dayOfWeek (1=Mon..7=Sun)
            int dow = date.getDayOfWeek().getValue();

            for (ScheduleTemplate template : templates) {
                if (template.getDayOfWeek() != dow) continue;

                // 检查是否已存在该排班
                boolean exists = existing.stream().anyMatch(e ->
                        e.getDoctorId().equals(doctorId) &&
                        e.getScheduleDate().equals(date) &&
                        e.getTimePeriod().equals(template.getTimePeriod()));
                if (exists) continue;

                boolean isHoliday = holidayDates.contains(date);

                // 创建日排班
                DoctorSchedule schedule = new DoctorSchedule();
                schedule.setDoctorId(doctorId);
                schedule.setDepartmentId(doctor.getDepartmentId());
                schedule.setScheduleDate(date);
                schedule.setTimePeriod(template.getTimePeriod());
                schedule.setTotalSlots(template.getTotalSlots());
                schedule.setBookedSlots(0);
                schedule.setExtraSlots(0);
                schedule.setStatus(isHoliday ? "HOLIDAY" : "NORMAL");
                scheduleMapper.insert(schedule);

                // 生成号源
                generateSlots(schedule, template);

                result.add(schedule);
                log.info("生成排班: doctor={}, date={}, period={}, slots={}, holiday={}",
                        doctorId, date, template.getTimePeriod(), template.getTotalSlots(), isHoliday);
            }
        }

        auditService.log("GENERATE_SCHEDULE", "DOCTOR", doctorId,
                String.format("{\"start\":\"%s\",\"end\":\"%s\",\"count\":%d}", start, end, result.size()));

        return result;
    }

    private void generateSlots(DoctorSchedule schedule, ScheduleTemplate template) {
        LocalTime time = template.getStartTime();
        int interval = template.getSlotInterval();
        boolean isHoliday = "HOLIDAY".equals(schedule.getStatus());

        for (int i = 1; i <= template.getTotalSlots(); i++) {
            ScheduleSlot slot = new ScheduleSlot();
            slot.setScheduleId(schedule.getId());
            slot.setDoctorId(schedule.getDoctorId());
            slot.setDepartmentId(schedule.getDepartmentId());
            slot.setSlotDate(schedule.getScheduleDate());
            slot.setSlotTime(time);
            slot.setSlotNo(i);
            slot.setStatus(isHoliday ? SlotStatus.RELEASED.name() : SlotStatus.AVAILABLE.name());
            slot.setIsExtra(0);
            slot.setVersion(0);
            slotMapper.insert(slot);

            time = time.plusMinutes(interval);
        }
    }

    @Override
    @Transactional
    public int applyHolidaySuspension(LocalDate date, Long departmentId) {
        LambdaQueryWrapper<DoctorSchedule> qw = new LambdaQueryWrapper<>();
        qw.eq(DoctorSchedule::getScheduleDate, date)
          .eq(DoctorSchedule::getStatus, "NORMAL");
        if (departmentId != null) {
            qw.eq(DoctorSchedule::getDepartmentId, departmentId);
        }
        List<DoctorSchedule> affected = scheduleMapper.selectList(qw);

        int count = 0;
        for (DoctorSchedule schedule : affected) {
            schedule.setStatus("HOLIDAY");
            scheduleMapper.updateById(schedule);

            // 释放该排班下所有可用号源
            slotMapper.batchRelease(schedule.getDoctorId(), date, date);
            count++;
        }

        auditService.log("HOLIDAY_SUSPENSION", "SCHEDULE", null,
                String.format("{\"date\":\"%s\",\"deptId\":%s,\"affected\":%d}", date, departmentId, count));

        return count;
    }

    @Override
    @Transactional
    public ScheduleSlot addExtraSlot(ExtraSlotRequest request) {
        DoctorSchedule schedule = scheduleMapper.selectById(request.getScheduleId());
        if (schedule == null) {
            throw BusinessException.scheduleNotFound();
        }

        // 生成加号号源
        ScheduleSlot slot = new ScheduleSlot();
        slot.setScheduleId(schedule.getId());
        slot.setDoctorId(schedule.getDoctorId());
        slot.setDepartmentId(schedule.getDepartmentId());
        slot.setSlotDate(request.getSlotDate() != null ? request.getSlotDate() : schedule.getScheduleDate());
        slot.setSlotTime(request.getSlotTime());
        slot.setSlotNo(schedule.getTotalSlots() + schedule.getExtraSlots() + 1);
        slot.setStatus(SlotStatus.AVAILABLE.name());
        slot.setIsExtra(1);
        slot.setVersion(0);
        slotMapper.insert(slot);

        // 更新排班加号计数
        schedule.setExtraSlots(schedule.getExtraSlots() + 1);
        scheduleMapper.updateById(schedule);

        auditService.log("ADD_EXTRA_SLOT", "SLOT", slot.getId(),
                String.format("{\"doctorId\":%d,\"date\":\"%s\",\"time\":\"%s\"}",
                        schedule.getDoctorId(), slot.getSlotDate(), slot.getSlotTime()));

        log.info("加号成功: doctor={}, date={}, time={}, slotNo={}",
                schedule.getDoctorId(), slot.getSlotDate(), slot.getSlotTime(), slot.getSlotNo());

        return slot;
    }

    @Override
    public List<ScheduleSlot> getSlotsByDoctorAndDate(Long doctorId, LocalDate date) {
        return slotMapper.findAvailable(doctorId, date);
    }

    @Override
    public List<DoctorSchedule> getSchedules(Long doctorId, LocalDate start, LocalDate end) {
        return scheduleMapper.findByDoctorAndDateRange(doctorId, start, end);
    }
}
