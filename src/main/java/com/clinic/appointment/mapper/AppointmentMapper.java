package com.clinic.appointment.mapper;

import com.clinic.appointment.model.entity.Appointment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface AppointmentMapper {

    int insert(Appointment appointment);

    int update(Appointment appointment);

    Appointment selectById(Long id);

    List<Appointment> selectByPatientId(String patientId);

    Appointment selectBySlotId(@Param("slotId") Long slotId);

    List<Appointment> selectByScheduleId(Long scheduleId);

    List<Appointment> selectActiveByScheduleId(@Param("scheduleId") Long scheduleId);

    int updateStatus(@Param("id") Long id,
                     @Param("status") String status);

    List<Appointment> selectByDoctorAndDate(@Param("doctorId") Long doctorId,
                                             @Param("scheduleDate") LocalDate scheduleDate);

    int batchUpdateStatus(@Param("ids") List<Long> ids,
                          @Param("status") String status,
                          @Param("reason") String reason);
}
