package com.clinic.appointment.mapper;

import com.clinic.appointment.model.entity.ScheduleTemplate;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ScheduleTemplateMapper {

    int insert(ScheduleTemplate scheduleTemplate);

    int update(ScheduleTemplate scheduleTemplate);

    ScheduleTemplate selectById(Long id);

    List<ScheduleTemplate> selectByDoctorId(Long doctorId);

    List<ScheduleTemplate> selectByDayOfWeek(int dayOfWeek);

    List<ScheduleTemplate> selectEnabledByDayOfWeek(int dayOfWeek);
}
