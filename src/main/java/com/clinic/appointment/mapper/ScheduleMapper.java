package com.clinic.appointment.mapper;

import com.clinic.appointment.model.entity.Schedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ScheduleMapper {

    int insert(Schedule schedule);

    int update(Schedule schedule);

    Schedule selectById(Long id);

    Schedule selectByDoctorAndDate(@Param("doctorId") Long doctorId,
                                   @Param("scheduleDate") LocalDate scheduleDate);

    List<Schedule> selectByDateRange(@Param("startDate") LocalDate startDate,
                                     @Param("endDate") LocalDate endDate);

    List<Schedule> selectByDoctorAndDateRange(@Param("doctorId") Long doctorId,
                                              @Param("startDate") LocalDate startDate,
                                              @Param("endDate") LocalDate endDate);

    int updateStatus(@Param("id") Long id,
                     @Param("status") String status);

    int incrementBookedCount(@Param("id") Long id);

    int decrementBookedCount(@Param("id") Long id);

    Schedule selectActiveByDoctorAndDate(@Param("doctorId") Long doctorId,
                                         @Param("scheduleDate") LocalDate scheduleDate,
                                         @Param("period") String period);
}
