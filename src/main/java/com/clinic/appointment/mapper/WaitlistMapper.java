package com.clinic.appointment.mapper;

import com.clinic.appointment.model.entity.Waitlist;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WaitlistMapper {

    int insert(Waitlist waitlist);

    int update(Waitlist waitlist);

    Waitlist selectById(Long id);

    List<Waitlist> selectByScheduleIdAndStatus(@Param("scheduleId") Long scheduleId,
                                               @Param("status") String status);

    Waitlist selectFirstWaiting(@Param("scheduleId") Long scheduleId);

    List<Waitlist> selectByPatientId(String patientId);

    int updateStatus(@Param("id") Long id,
                     @Param("status") String status);

    int countByScheduleId(@Param("scheduleId") Long scheduleId);

    Integer selectMaxPosition(@Param("scheduleId") Long scheduleId);
}
