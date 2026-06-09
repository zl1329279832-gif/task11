package com.clinic.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clinic.appointment.domain.entity.Appointment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface AppointmentMapper extends BaseMapper<Appointment> {

    @Select("SELECT * FROM appointment WHERE doctor_id = #{doctorId} " +
            "AND slot_date >= #{fromDate} AND status IN ('PENDING','CONFIRMED')")
    List<Appointment> findActiveByDoctorFromDate(@Param("doctorId") Long doctorId,
                                                  @Param("fromDate") LocalDate fromDate);

    @Select("SELECT * FROM appointment WHERE doctor_id = #{doctorId} " +
            "AND slot_date = #{date} AND status IN ('PENDING','CONFIRMED')")
    List<Appointment> findByDoctorAndDate(@Param("doctorId") Long doctorId,
                                           @Param("date") LocalDate date);

    @Select("SELECT * FROM appointment WHERE patient_id = #{patientId} " +
            "AND status IN ('PENDING','CONFIRMED') ORDER BY slot_date, slot_time")
    List<Appointment> findActiveByPatient(@Param("patientId") Long patientId);

    @Select("SELECT COUNT(*) FROM appointment WHERE patient_id = #{patientId} " +
            "AND slot_date = #{date} AND status IN ('PENDING','CONFIRMED')")
    long countByPatientAndDate(@Param("patientId") Long patientId, @Param("date") LocalDate date);
}
