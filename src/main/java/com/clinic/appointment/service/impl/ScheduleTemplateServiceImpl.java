package com.clinic.appointment.service.impl;

import com.clinic.appointment.mapper.ScheduleTemplateMapper;
import com.clinic.appointment.model.entity.ScheduleTemplate;
import com.clinic.appointment.service.ScheduleTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ScheduleTemplateServiceImpl implements ScheduleTemplateService {

    private final ScheduleTemplateMapper scheduleTemplateMapper;

    @Override
    @Transactional
    public ScheduleTemplate create(ScheduleTemplate scheduleTemplate) {
        scheduleTemplateMapper.insert(scheduleTemplate);
        return scheduleTemplate;
    }

    @Override
    @Transactional
    public ScheduleTemplate update(ScheduleTemplate scheduleTemplate) {
        scheduleTemplateMapper.update(scheduleTemplate);
        return scheduleTemplate;
    }

    @Override
    public ScheduleTemplate getById(Long id) {
        return scheduleTemplateMapper.selectById(id);
    }

    @Override
    public List<ScheduleTemplate> getByDoctorId(Long doctorId) {
        return scheduleTemplateMapper.selectByDoctorId(doctorId);
    }

    @Override
    public List<ScheduleTemplate> getEnabledByDayOfWeek(int dayOfWeek) {
        return scheduleTemplateMapper.selectEnabledByDayOfWeek(dayOfWeek);
    }
}
