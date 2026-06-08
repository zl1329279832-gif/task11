package com.clinic.appointment.service;

import com.clinic.appointment.model.entity.ScheduleTemplate;

import java.util.List;

public interface ScheduleTemplateService {

    ScheduleTemplate create(ScheduleTemplate scheduleTemplate);

    ScheduleTemplate update(ScheduleTemplate scheduleTemplate);

    ScheduleTemplate getById(Long id);

    List<ScheduleTemplate> getByDoctorId(Long doctorId);

    List<ScheduleTemplate> getEnabledByDayOfWeek(int dayOfWeek);
}
