package com.clinic.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clinic.appointment.domain.entity.AppointmentResource;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AppointmentResourceMapper extends BaseMapper<AppointmentResource> {

    @Select("SELECT * FROM appointment_resource WHERE appointment_id = #{appointmentId}")
    List<AppointmentResource> findByAppointment(@Param("appointmentId") Long appointmentId);

    @Delete("DELETE FROM appointment_resource WHERE appointment_id = #{appointmentId}")
    int deleteByAppointment(@Param("appointmentId") Long appointmentId);
}
