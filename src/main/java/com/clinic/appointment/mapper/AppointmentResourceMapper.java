package com.clinic.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clinic.appointment.domain.entity.AppointmentResource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AppointmentResourceMapper extends BaseMapper<AppointmentResource> {

    @Select("SELECT * FROM appointment_resource WHERE appointment_id = #{appointmentId} " +
            "AND status = 'BOOKED'")
    List<AppointmentResource> findByAppointment(@Param("appointmentId") Long appointmentId);

    @Update("UPDATE appointment_resource SET status = 'RELEASED', update_time = NOW() " +
            "WHERE appointment_id = #{appointmentId} AND status = 'BOOKED'")
    int releaseByAppointment(@Param("appointmentId") Long appointmentId);
}
